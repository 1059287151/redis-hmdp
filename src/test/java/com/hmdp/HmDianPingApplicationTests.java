package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class HmDianPingApplicationTests {

    private final ShopServiceImpl shopService;
    @Autowired
    public HmDianPingApplicationTests(ShopServiceImpl shopService) {
        this.shopService = shopService;
    }

    @Test
    public void test(){
        shopService.saveShopRedis(1L, 1000L);
    }
}
