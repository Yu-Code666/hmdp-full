package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 基于Redis的简单分布式锁实现
 */
public class SimpleRedisLock implements ILock {

    /**
     * 锁的名称
     */
    private String name;
    /**
     * Redis操作模板
     */
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 构造函数
     * @param name 锁名称
     * @param stringRedisTemplate Redis操作模板
     */
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 锁的键前缀
     */
    private static final String KEY_PREFIX = "lock:";
    /**
     * 线程标识前缀，使用UUID确保不同JVM实例的唯一性
     */
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    /**
     * 解锁的Lua脚本对象
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        // 设置Lua脚本的位置
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        // 设置脚本返回值类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标示，确保锁的唯一性
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁，使用setIfAbsent(SET NX)命令，实现互斥性
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        // 避免自动拆箱可能导致的空指针异常
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 调用lua脚本，确保释放锁的原子性和安全性
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),  // KEYS参数列表
                ID_PREFIX + Thread.currentThread().getId());   // ARGV参数列表
    }
    /*@Override
    public void unlock() {
        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁中的标示
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 判断标示是否一致
        if(threadId.equals(id)) {
            // 释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}
