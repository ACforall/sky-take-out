package com.sky.controller.admin;

import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CachePut;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 菜品管理
 */
@RestController
@RequestMapping("/admin/dish")
@Api(tags = "菜品相关接口")
@Slf4j
public class DishController {
    @Autowired
    private DishService dishService;
    @Autowired
    private RedisTemplate redisTemplate;

    @PostMapping
    @ApiOperation("新增菜品")
    public Result save(@RequestBody DishDTO dishDTO){
        dishService.saveWithFlavor(dishDTO);
        //清理缓存数据
        cleanCache("dish_"+dishDTO.getCategoryId());//只要删除就行了，相当于删掉了这个分类的redis缓存，下次查询的时候发现redis里没有，然后就去数据库里再次查询，放到redis里
        return Result.success();
    }

    @GetMapping("/page")
    @ApiOperation("菜品分页查询")
    public Result<PageResult> page(DishPageQueryDTO dishPageQueryDTO){
        PageResult pageResult=dishService.pageQuery(dishPageQueryDTO);
        return Result.success(pageResult);
    }

    @DeleteMapping
    @ApiOperation("批量删除菜品")
    public Result delete(@RequestParam List<Long> ids){
        log.info("ids:{}",ids);
        dishService.deleteBatch(ids);
        //多个菜品可能不是同一分类下的
        //直接删掉所有数据dish
        cleanCache("dish_*");
        return Result.success();
    }

    @GetMapping("/{id}")
    @ApiOperation("根据id查询菜品")
    public Result<DishVO> getById(@PathVariable Long id){
        DishVO dishVO=dishService.getByIdWithFlavor(id);
        return Result.success(dishVO);
    }

    @PutMapping
    @ApiOperation("修改菜品")
    public Result update(@RequestBody DishDTO dishDTO){
        dishService.updateWithFlavor(dishDTO);
        //如果修改分类了，那就涉及两个分类下面的缓存修改。这里也简化处理：将所有菜品缓存数据清理掉
        cleanCache("dish_*");
        return Result.success();
    }

    @PostMapping("/status/{status}")
    @ApiOperation("菜品启售停售")
    public Result startOrStop(Long id, @PathVariable Integer status){
        dishService.startOrStop(id, status);
        cleanCache("dish_*");
        return Result.success();
    }

    @GetMapping("/list")
    @ApiOperation("根据分类id查询菜品")
    public Result<List<Dish>> getByCategoryId(Long categoryId){
        List<Dish> dishes=dishService.getByCategoryId(categoryId);
        return Result.success(dishes);
    }

    private void cleanCache(String pattern){
        Set keys=redisTemplate.keys(pattern);
        redisTemplate.delete(keys);
    }
}
