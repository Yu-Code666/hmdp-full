package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional // 添加事务注解,确保数据的一致性
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券基本信息到tb_voucher表
        save(voucher);
        // 创建秒杀优惠券对象并设置秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        // 设置关联的优惠券id
        seckillVoucher.setVoucherId(voucher.getId());
        // 设置秒杀库存
        seckillVoucher.setStock(voucher.getStock());
        // 设置秒杀开始时间
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        // 设置秒杀结束时间
        seckillVoucher.setEndTime(voucher.getEndTime());
        // 保存秒杀信息到tb_seckill_voucher表
        seckillVoucherService.save(seckillVoucher);
        // 保存秒杀库存到Redis中,key为前缀+优惠券id,value为库存值
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
    }
}
