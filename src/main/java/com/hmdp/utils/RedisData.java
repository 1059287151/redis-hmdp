package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    // LocalDateTime: 日期对象并且线程安全
    private LocalDateTime expireTime;
    private Object data;
}
