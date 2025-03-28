package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            // 如果博客不存在，返回失败结果
            return Result.fail("笔记不存在！");
        }
        // 2.查询blog有关的用户
        queryBlogUser(blog);  // 调用方法获取博客作者信息
        // 3.查询blog是否被点赞
        isBlogLiked(blog);  // 检查当前登录用户是否对该博客点过赞
        // 返回查询到的博客信息
        return Result.ok(blog);
    }

    /**
     * 判断博客是否被当前用户点赞
     * @param blog 博客对象
     */
    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        Long userId = user.getId();
        // 2.判断当前登录用户是否已经点赞
        String key = "blog:liked:" + blog.getId();
        // 从Redis的ZSet中查询用户的点赞记录，score不为null则表示用户已点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        // 设置博客的isLike属性，标记当前用户是否点赞过该博客
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 3.如果未点赞，可以点赞
            // 3.1.数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2.保存用户到Redis的set集合  zadd key value score
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4.如果已点赞，取消点赞
            // 4.1.数据库点赞数 -1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2.把用户从Redis的set集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询博客点赞用户列表
     * @param id 博客ID
     * @return 点赞用户列表
     */
    @Override
    public Result queryBlogLikes(Long id) {
        // 构建Redis键，用于存储博客点赞信息
        String key = BLOG_LIKED_KEY + id;
        // 1.查询top5的点赞用户 zrange key 0 4
        // 从Redis的有序集合中获取分数最低的前5个用户（即最早点赞的5个用户）
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        // 如果没有人点赞，则返回空列表
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出其中的用户id
        // 将字符串类型的用户ID转换为Long类型
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 将用户ID列表转换为逗号分隔的字符串，用于SQL查询
        String idStr = StrUtil.join(",", ids);
        // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        // 查询数据库获取用户信息，并保持与Redis中相同的顺序
        // ORDER BY FIELD确保返回的用户顺序与传入的id顺序一致
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                // 将User对象转换为UserDTO对象，减少数据传输量
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4.返回点赞用户列表
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店笔记
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败!");
        }
        // 3.查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 4.推送笔记id给所有粉丝
        for (Follow follow : follows) {
            // 4.1.获取粉丝id
            Long userId = follow.getUserId();
            // 4.2.推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 5.返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        // 构建用户的收件箱key
        String key = FEED_KEY + userId;
        // 使用Redis的ZSet数据结构，按照分数（时间戳）从大到小查询收件箱中的数据
        // max参数表示查询分数小于等于max的数据
        // offset和2分别表示跳过offset条数据后取2条数据
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3.非空判断
        // 如果收件箱为空，则直接返回空结果
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.解析数据：blogId、minTime（时间戳）、offset
        // 创建一个列表存储博客ID
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; // 记录本次查询的最小时间戳
        int os = 1; // 记录与最小时间戳相同的元素个数，用于下次查询的偏移量
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 遍历查询结果
            // 4.1.获取id
            // 将博客ID添加到列表中
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2.获取分数(时间戳）
            long time = tuple.getScore().longValue();
            // 如果当前元素的时间戳与最小时间戳相同，则计数器加1
            if(time == minTime){
                os++;
            }else{
                // 如果不同，则更新最小时间戳，并重置计数器为1
                minTime = time;
                os = 1;
            }
        }

        // 5.根据id查询blog
        // 将ID列表转换为逗号分隔的字符串，用于SQL查询
        String idStr = StrUtil.join(",", ids);
        // 使用MyBatis-Plus查询博客信息，并保持与Redis中相同的顺序
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            // 查询并设置博客作者的信息
            queryBlogUser(blog);
            // 5.2.查询blog是否被点赞
            // 查询当前用户是否对该博客点赞
            isBlogLiked(blog);
        }

        // 6.封装并返回
        // 创建滚动分页结果对象
        ScrollResult r = new ScrollResult();
        // 设置博客列表
        r.setList(blogs);
        // 设置下一次查询的偏移量
        r.setOffset(os);
        // 设置下一次查询的最小时间戳
        r.setMinTime(minTime);

        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog) {
        // 获取博客作者的用户ID
        Long userId = blog.getUserId();
        // 根据用户ID查询用户信息
        User user = userService.getById(userId);
        // 将用户的昵称设置到博客对象中
        blog.setName(user.getNickName());
        // 将用户的头像设置到博客对象中
        blog.setIcon(user.getIcon());
    }
}
