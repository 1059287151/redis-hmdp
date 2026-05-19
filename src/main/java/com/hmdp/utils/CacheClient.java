package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private final StringRedisTemplate stringRedisTemplate;
    @Autowired
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    //方法1：将任意Java对象序列化为JSON，并存储到String类型的Key中，并可以设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }
    //方法2：将任意Java对象序列化为JSON，并存储在String类型的Key中，并可以设置逻辑过期时间，用于处理缓存击穿问题
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit){
        // 需要设置逻辑过期时间，所以需要redisData
        RedisData redisData = new RedisData();
        // redis的data就是传进来的value
        redisData.setData(value);
        // 逻辑过期时间就是当前时间加上传进来的时间，用timeUnit转换为秒在加
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        //由于逻辑过期，这里救不要设置过期时间，只存一下key或者value就好，同时注意value是redisData类型
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }
    //方法3：根据指定的Key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit){
        //先从redis查询，这里常量值是固定前缀加ID
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //如果不为空（查询到了），那就直接返回
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json,type);
        }
        //如果 json 等于空字符串 ""（因为前面已经排除了非空的情况）
        //说明之前查询数据库时发现数据不存在，所以缓存了一个空值
        //此时直接返回 null，避免再次查询数据库
        if(json != null){
            return null;
        }
        // 去数据库查，查用我们定义的参数
        R r = dbFallback.apply(id);
        // 查不到，则将空值写入redis
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //查到了则转换为json字符串
        String jsonStr = JSONUtil.toJsonStr(r);
        // 并存入redis，设置ttl
        this.set(key, jsonStr, time, timeUnit);
        return r;
    }

    //方法4：根据指定的Key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit){
        // 从redis中查询缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 如果未命中，则返回空
        if(StrUtil.isBlank(json)){
            return null;
        }
        // 命中，将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            // 未过期则直接返回
            return r;
        }
        //过期，尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean flag = tryLock(lockKey);
        //获取到了锁
        if(flag){
            //开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R tmp = dbFallback.apply(id);
                    this.set(key,tmp,time,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
            // 直接返回商铺信息
            return r;
        }
        //未获取到锁，直接返回
        return r;
    }

    //方法5：根据指定的Key查询缓存，并反序列化为指定类型，需要利用互斥锁解决缓存击穿问题
    public <R, ID> R queryWithMutex(String keyPrefix,ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit){
        // 先从redis查询
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 如果不为空，转换为shop类型，直接返回
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        if(json != null){
            return null;
        }
        R r = null;
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            // 否则去数据库查
            boolean flag = tryLock(lockKey);
            if(!flag){
                Thread.sleep(50);
                return queryWithLogicalExpire(keyPrefix, id, type, dbFallback, time, timeUnit);
            }
            r = dbFallback.apply(id);
            // 如果查不到，则写入控制
            if(r == null){
                stringRedisTemplate.opsForValue().set(key, "", time, timeUnit);
                return null;
            }
            // 并存入redis，设置ttl
            this.set(key, r, time, timeUnit);
        }catch (InterruptedException e){
            throw new RuntimeException(e);
        }finally {
            unLock(lockKey);
        }
        return r;
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
