package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /**
     * 关注或取消关注用户的接口
     * 
     * 这是一个处理关注/取消关注功能的控制器方法。
     * 
     * @PutMapping("/{id}/{isFollow}") 注解表示这个方法处理PUT请求，
     * 路径中包含两个路径变量：id（要关注的用户ID）和isFollow（是否关注）
     * 
     * @param followUserId 被关注用户的ID，通过@PathVariable从URL路径中的{id}部分获取
     * @param isFollow 是否关注该用户，true表示关注，false表示取消关注，通过@PathVariable从URL路径中的{isFollow}部分获取
     * @return 返回关注/取消关注操作的结果，实际调用followService服务层的follow方法处理业务逻辑
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    /**
     * 查询当前用户是否关注了某个用户的接口
     * 
     * 这是一个处理查询关注状态的控制器方法。
     * 
     * @GetMapping("/or/not/{id}") 注解表示这个方法处理GET请求，
     * 路径中包含一个路径变量：id（要查询关注状态的用户ID）
     * 
     * @param followUserId 被查询关注状态的用户ID，通过@PathVariable从URL路径中的{id}部分获取
     * @return 返回查询结果，包含是否已关注该用户的信息，实际调用followService服务层的isFollow方法处理业务逻辑
     * 
     * 当前登录用户可以通过这个接口查询自己是否已经关注了指定的用户，
     * 前端可以根据返回结果显示"关注"或"已关注"按钮状态
     */
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    /**
     * 查询共同关注的接口
     * 
     * 这是一个处理查询共同关注用户的控制器方法。
     * 
     * @GetMapping("/common/{id}") 注解表示这个方法处理GET请求，
     * 路径中包含一个路径变量：id（要查询共同关注的目标用户ID）
     * 
     * @param id 目标用户的ID，通过@PathVariable从URL路径中的{id}部分获取
     * @return 返回当前登录用户与目标用户的共同关注列表，实际调用followService服务层的followCommons方法处理业务逻辑
     * 
     * 该接口用于查询当前登录用户与指定用户共同关注的所有用户列表，
     * 可用于社交应用中展示共同好友/共同关注功能
     */
    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id){
        return followService.followCommons(id);
    }
}
