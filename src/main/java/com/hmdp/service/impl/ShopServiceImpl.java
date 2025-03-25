package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 根据id查询商铺信息
        // 使用缓存客户端查询，解决缓存穿透问题
        // 参数说明：缓存key前缀、商铺id、返回值类型、查询函数、缓存时间、时间单位
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 以下是其他解决缓存击穿的方案(已注释)
        // 方案1:使用互斥锁解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 方案2:使用逻辑过期解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        // 判断商铺是否存在
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 返回查询到的商铺信息
        return Result.ok(shop);
    }

    @Override
    @Transactional // 添加事务注解,保证数据库操作的原子性
    public Result update(Shop shop) {
        // 获取店铺id
        Long id = shop.getId();
        // 判断店铺id是否为空
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库中的店铺信息
        updateById(shop);
        // 2.删除Redis缓存,保证缓存一致性
        // 这里采用删除缓存的策略,而不是更新缓存,是为了避免缓存和数据库不一致的问题
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        // 返回更新成功的结果
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE; // 计算起始索引
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE; // 计算结束索引

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId; // 构建Redis GEO数据结构的key
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key, // Redis的key
                        GeoReference.fromCoordinate(x, y), // 用户当前位置坐标
                        new Distance(5000), // 搜索半径，单位米
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end) // 包含距离信息并限制返回数量
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList()); // 如果没有结果，返回空列表
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent(); // 获取搜索结果内容
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size()); // 创建列表存储店铺ID
        Map<String, Distance> distanceMap = new HashMap<>(list.size()); // 创建Map存储店铺ID与距离的映射关系
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName(); // 从GEO结果中获取店铺ID
            ids.add(Long.valueOf(shopIdStr)); // 将字符串ID转为Long类型并添加到列表
            // 4.3.获取距离
            Distance distance = result.getDistance(); // 获取当前店铺与用户的距离
            distanceMap.put(shopIdStr, distance); // 将店铺ID和距离信息存入Map
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids); // 将ID列表转为逗号分隔的字符串，用于SQL查询
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list(); // 查询店铺信息并保持与Redis返回顺序一致
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue()); // 设置每个店铺与用户的距离
        }
        // 6.返回
        return Result.ok(shops); // 返回查询结果
    }
}
