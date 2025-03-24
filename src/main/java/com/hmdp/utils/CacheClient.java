package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透解决方案
     * @param keyPrefix key前缀
     * @param id 查询的id
     * @param type 返回值类型
     * @param dbFallback 查询数据库的函数
     * @param time 过期时间
     * @param unit 时间单位
     * @return 泛型R
     * @param <R> 返回值类型
     * @param <ID> id类型
     */
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回,将JSON字符串反序列化为对象
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值,这里的null是""空字符串
        if (json != null) {
            // 返回错误信息,缓存穿透的时候会返回
            return null;
        }

        // 4.不存在，根据id查询数据库,这里使用函数式编程
        R r = dbFallback.apply(id);
        // 5.数据库也不存在，返回错误
        if (r == null) {
            // 将空值写入redis,解决缓存穿透问题
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // 6.存在，写入redis,设置过期时间
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 使用逻辑过期解决缓存击穿问题
     * @param keyPrefix 缓存key前缀
     * @param id 查询的id
     * @param type 返回值类型
     * @param dbFallback 查询数据库的函数
     * @param time 逻辑过期时间
     * @param unit 时间单位
     * @return 泛型R
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在,不存在直接返回null
        if (StrUtil.isBlank(json)) {
            // 3.不存在，直接返回null,说明这个key没有预热
            return null;
        }
        // 4.命中缓存,需要先把json反序列化为对象,包含过期时间和实际数据
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        // 将数据转换为指定的类型
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        // 获取逻辑过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断缓存是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回缓存中的数据
            return r;
        }
        // 5.2.已过期，需要进行缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁,避免多个线程同时重建
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock){
            // 6.3.成功获取锁,开启独立线程执行缓存重建,避免用户等待
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库获取最新数据
                    R newR = dbFallback.apply(id);
                    // 将新数据写入Redis,并设置逻辑过期时间
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放互斥锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4.返回过期的商铺信息,实现缓存击穿的"逻辑过期"策略
        return r;
    }

    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 构造Redis键
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回,将JSON转为Java对象
            return JSONUtil.toBean(shopJson, type);
        }
        // 判断命中的是否是空值,用于解决缓存穿透问题
        if (shopJson != null) {
            // 返回一个错误信息,说明数据库中也不存在这条数据
            return null;
        }

        // 4.实现缓存重建
        // 4.1.获取互斥锁,使用分布式锁解决缓存击穿问题
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            // 尝试获取锁
            boolean isLock = tryLock(lockKey);
            // 4.2.判断是否获取成功
            if (!isLock) {
                // 4.3.获取锁失败，休眠50毫秒后重试,避免频繁请求数据库
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 4.4.获取锁成功，根据id查询数据库
            r = dbFallback.apply(id);
            // 5.不存在，返回错误
            if (r == null) {
                // 将空值写入redis,避免缓存穿透,设置较短的过期时间
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误信息
                return null;
            }
            // 6.存在，写入redis缓存,并设置过期时间
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 7.释放互斥锁
            unlock(lockKey);
        }
        // 8.返回数据库查询结果
        return r;
    }

    /**
     * 尝试获取分布式锁
     * @param key 锁的key
     * @return 是否获取成功
     */
    private boolean tryLock(String key) {
        // 使用Redis的setNX命令实现分布式锁
        // 参数说明:key-锁的key值,value-锁的值(这里用1),10-锁的过期时间,TimeUnit.SECONDS-时间单位(秒)
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 返回获取锁的结果,使用BooleanUtil工具类防止空指针
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放分布式锁
     * @param key 锁的key值
     */
    private void unlock(String key) {
        // 使用Redis的delete命令删除锁,实现解锁操作
        stringRedisTemplate.delete(key);
    }
}
