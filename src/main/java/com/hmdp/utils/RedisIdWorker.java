package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    private final StringRedisTemplate stringRedisTemplate;
    @Autowired
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //设置起始时间，我们这里设置为2022.01.01 00：00：00
    private final static Long BEGIN_TIMESTAMP = 1640995200L;
    //序列化长度
    private final static Long COUNT_BIT = 32L;

    public long nextId(String keyPrefix){
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long currentSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = currentSecond - BEGIN_TIMESTAMP;
        // 生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("inc:" + keyPrefix + ":" + date);
        //高32位(timeStamp)        低32位(count)
        //   [tttttttttttttttttttt] [cccccccccccccccccccc]
        return timeStamp << COUNT_BIT | count;
    }

}
