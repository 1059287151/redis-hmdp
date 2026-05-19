package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.utils.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
@RequiredArgsConstructor
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    //TODO 封装了redis的工具类，但是没有调，后面调试
    private final StringRedisTemplate stringRedisTemplate;
    //这里需要声明一个线程池，因为下面我们需要新建一个现成来完成重构缓存
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    @Override
    public Result queryById(Long id) {
        //Shop shop = queryWithMutex(id);
        Shop shop = queryWithLogicalExpire(id);
        return Result.ok(shop);
    }
    // 互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id) {
        // 从redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在直接返回
            //System.out.println("redis");
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 如果查询到的是空字符串，则说明是我们缓存的空数据
        if("".equals(shopJson)){
            return null;
        }
        /*// 不存在根据id查询数据库
        Shop shop = getById(id);
        // 不存在返回错误
        if(shop == null){
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        // 存在，写入redis
        // 把shop转换为JSON写入redis,并设置TTL
        System.out.println("mysql");
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);*/
        // 互斥锁解决雪崩问题
        String lockKey = "lock:shop" + id;
        Shop shop = null;
        try {
            // 实现缓存重建
            // 获取互斥锁
            boolean isLock = tryLock(lockKey);
            // 判断是否成功
            if(!isLock){
                // 失败则睡一会在重试
                Thread.sleep(50);
                // 递归
                return queryWithMutex(id);
            }
            // 成功，根据id查询数据库
            shop = getById(id);
            // 模拟延迟
            Thread.sleep(200);
            // 不存在返回错误
            if(shop == null){
                stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 存在写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            unLock(lockKey);
        }
        return shop;
    }
    // 逻辑过期解决缓存击穿
    public void saveShopRedis(Long id, Long expireSeconds){
        // 查询数据
        Shop shop = getById(id);
        // 封装逻辑过期事件
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));// 过期时间
        // 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
    public Shop queryWithLogicalExpire(Long id){
        // 从redis商铺查询缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isBlank(shopJson)){
            return null;
        }
        // 命中需要把json反序列化为对象
        // redisData没有数据
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            // 未过期返回
            return shop;
        }

        // 已过期，需要缓存重建
        // 缓存重建
        // 获取互斥锁
        String LockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(LockKey);
        // 判断是否获取互斥锁成功
        if (isLock){
            // 成功则开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存，设置未20L
                    saveShopRedis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(LockKey);
                }
            });
        }
        return shop;
    }

    @Override
    public Result updateByShop(Shop shop) {
        // 判断是否未空
        if(shop.getId() == null){
            return Result.fail("店铺id不能为空");
        }
        // 先修改数据库
        updateById(shop);
        // 在删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    /**
     * 获取互斥锁
     * @param key 商品key
     * @return 是否获取到互斥锁
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     * @param key 商品keyz
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
