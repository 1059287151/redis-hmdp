package com.hmdp;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ShopServiceTest {
    @Autowired
    private ShopServiceImpl shopService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final Long TEST_SHOP_ID = 1L;

    @BeforeEach
    void setUp() {
        // 清理测试数据
        stringRedisTemplate.delete(CACHE_SHOP_KEY + TEST_SHOP_ID);
        stringRedisTemplate.delete("lock:shop" + TEST_SHOP_ID);
    }

    /**
     * 测试1：缓存命中场景 - Redis中有数据时直接返回
     */
    @Test
    void testCacheHit() {
        // 准备：预先在Redis中设置缓存
        Shop shop = new Shop();
        shop.setId(TEST_SHOP_ID);
        shop.setName("测试店铺");
        stringRedisTemplate.opsForValue().set(
                CACHE_SHOP_KEY + TEST_SHOP_ID,
                JSONUtil.toJsonStr(shop)
        );

        // 执行：查询店铺
        Shop result = shopService.queryWithMutex(TEST_SHOP_ID);

        // 验证：应该从Redis返回，不查数据库
        assertNotNull(result);
        assertEquals(TEST_SHOP_ID, result.getId());
        assertEquals("测试店铺", result.getName());
    }

    /**
     * 测试2：缓存穿透场景 - 数据库中存在数据
     */
    @Test
    void testCacheMissWithExistingData() {
        // 准备：确保Redis中没有缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + TEST_SHOP_ID);

        // 执行：第一次查询（会查数据库并写入缓存）
        Shop result1 = shopService.queryWithMutex(TEST_SHOP_ID);

        // 验证：应该从数据库查询到数据
        assertNotNull(result1);
        assertEquals(TEST_SHOP_ID, result1.getId());

        // 验证：Redis中应该有缓存了
        String cachedValue = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + TEST_SHOP_ID);
        assertNotNull(cachedValue);
        assertNotEquals("", cachedValue); // 不是空字符串

        // 执行：第二次查询（应该从Redis返回）
        Shop result2 = shopService.queryWithMutex(TEST_SHOP_ID);
        assertNotNull(result2);
        assertEquals(result1.getId(), result2.getId());
    }

    /**
     * 测试3：数据库不存在的数据 - 应该缓存空值
     */
    @Test
    void testCacheMissWithNonExistingData() {
        Long nonExistingId = 999999L;
        stringRedisTemplate.delete(CACHE_SHOP_KEY + nonExistingId);

        // 执行：查询不存在的店铺
        Shop result = shopService.queryWithMutex(nonExistingId);

        // 验证：应该返回null
        assertNull(result);

        // 验证：Redis中应该缓存了空字符串
        String cachedValue = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + nonExistingId);
        assertEquals("", cachedValue);
    }

    /**
     * 测试4：并发场景 - 模拟多个线程同时查询同一个key
     * 验证互斥锁是否生效，只有一个线程查数据库
     */
    @Test
    void testConcurrentQuery() throws InterruptedException {
        // 准备：确保Redis中没有缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + TEST_SHOP_ID);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        // 用于记录哪些线程执行了数据库查询（通过打印日志观察）
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    // 等待所有线程准备好
                    startLatch.await();

                    // 同时发起查询
                    Shop shop = shopService.queryWithMutex(TEST_SHOP_ID);
                    System.out.println(Thread.currentThread().getName() + " 查询结果: " + shop);

                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        // 释放所有线程，让它们同时执行
        startLatch.countDown();

        // 等待所有线程完成
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "并发测试超时");

        // 验证：最终Redis中应该有缓存
        String cachedValue = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + TEST_SHOP_ID);
        assertNotNull(cachedValue);
        assertNotEquals("", cachedValue);

        executor.shutdown();
    }

    /**
     * 测试5：缓存过期后的重建
     */
    @Test
    void testCacheRebuildAfterExpiry() throws InterruptedException {
        // 准备：先建立缓存
        Shop result1 = shopService.queryWithMutex(TEST_SHOP_ID);
        assertNotNull(result1);

        // 手动删除缓存模拟过期
        stringRedisTemplate.delete(CACHE_SHOP_KEY + TEST_SHOP_ID);

        // 执行：再次查询应该重新从数据库加载
        Shop result2 = shopService.queryWithMutex(TEST_SHOP_ID);
        assertNotNull(result2);
        assertEquals(result1.getId(), result2.getId());

        // 验证：缓存已重建
        String cachedValue = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + TEST_SHOP_ID);
        assertNotNull(cachedValue);
    }
}
