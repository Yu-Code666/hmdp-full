package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 秒杀优惠券下单
     * @param voucherId 优惠券id
     * @return 订单创建结果
     */
    @PostMapping("seckill/{id}") // 处理秒杀下单请求,路径为/voucher-order/seckill/{id}
    public Result seckillVoucher(@PathVariable("id") Long voucherId) { // @PathVariable注解用于获取URL中的id参数
        return voucherOrderService.seckillVoucher(voucherId); // 调用服务层处理秒杀下单逻辑
    }
}
