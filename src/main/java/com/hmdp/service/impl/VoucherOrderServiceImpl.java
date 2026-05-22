package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private final ISeckillVoucherService seckillVoucherService;
    private final RedisIdWorker redisIdWorker;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    @Override
    public Result seckillVoucher(Long voucherId) {
        LambdaQueryWrapper<SeckillVoucher> queryWrapper = new LambdaQueryWrapper<>();
        // 查询优惠卷
        //SELECT * FROM seckill_voucher WHERE voucher_id = ${}
        queryWrapper.eq(SeckillVoucher::getVoucherId, voucherId);
        SeckillVoucher seckillVoucher = seckillVoucherService.getOne(queryWrapper);
        // 判断秒杀是否开始
        if (LocalDateTime.now().isBefore(seckillVoucher.getBeginTime())){
            return Result.fail("秒杀还没有开始，请耐心等待");
        }
        // 判断秒杀是否结束
        if (LocalDateTime.now().isAfter(seckillVoucher.getEndTime())){
            return Result.fail("秒杀已经结束,下次请早点来");
        }
        // 判断库存是否充足
        if (seckillVoucher.getStock() < 1){
            return Result.fail("优惠卷已经被抢光了");
        }
        Long userId = UserHolder.getUser().getId();
        /*// 创建锁对象
        SimpleRedisLock redisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        // 获取锁对象
        boolean isLock = redisLock.tryLock(1200);*/
        RLock redisLock = redissonClient.getLock("order:" + userId);
        boolean isLock = redisLock.tryLock();
        // 加锁失败，说明当前用户开了多个线程抢优惠卷，但是由于key是SETNX，所以不能创建key，的等key的ttl过期或释放锁（删除key）
        if (!isLock) {
            return Result.fail("开多个线程抢票私募");
        }
        try {
            //获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            redisLock.unlock();
        }
    }
    @Transactional
    public Result createVoucherOrder(Long voucherId){
        // 一人一单逻辑
        Long userId = UserHolder.getUser().getId();
        long count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if (count > 0){
            return Result.fail("你已经抢过该订单了");
        }
        // 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                //.eq("stock", seckillVoucher.getStock())//乐观锁机制,但是这样只有一个线程会执行成功，其他都会报错
                .gt("stock", 0)//只要数据库中的库存大于0，都能顺利完成扣减库存操作
                .update();
        if (!success){
            return Result.fail("库存没有拉");
        }
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 设置订单id
        long orderId = redisIdWorker.nextId("order");
        // 设置用户id
        Long id = UserHolder.getUser().getId();
        // 设置代金卷id
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(id);
        // 将订单数据保存到表中
        save(voucherOrder);
        // 返回订单id
        return Result.ok(orderId);
        //执行到这里，锁已经被释放了，但是可能当前事务还未提交，如果此时有线程进来，不能确保事务不出问题
    }
}
