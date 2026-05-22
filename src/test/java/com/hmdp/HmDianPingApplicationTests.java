package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {

    private final ShopServiceImpl shopService;
    private final RedissonClient redissonClient;
    @Autowired
    public HmDianPingApplicationTests(ShopServiceImpl shopService, RedissonClient redissonClient) {
        this.shopService = shopService;
        this.redissonClient = redissonClient;
    }

    @Test
    public void test(){
        shopService.saveShopRedis(1L, 1000L);
    }

    @Test
    public void testRedisson() throws InterruptedException {
        // 获取可重入锁
        RLock lock = redissonClient.getLock("anyLock");
        // 尝试获取锁，三个参数分别是：获取锁的最大等待时间（期间会重试），锁的自动释放时间，时间单位
        boolean success = lock.tryLock(1, 10, TimeUnit.SECONDS);
        // 判断获取锁是否成功
        if (success) {
            try {
                System.out.println("执行业务");
            } finally {
                // 释放锁
                lock.unlock();

            }
        }

    }
}
