package com.example.demo.service.cache;

import com.example.demo.model.BrandVO;

import java.util.List;

public interface BrandCacheService {
    /**
     * 根据品牌ID获取高频品牌详情（读高频）
     */
    BrandVO getBrandById(Long brandId);

    /**
     * 获取高频商品系列列表（读高频）
     */
    List<String> getSeriesListByBrandId(Long brandId);

    /**
     * 更新品牌信息（写低频，修改数据库并淘汰/更新缓存）
     */
    void updateBrand(BrandVO brandVO);

    /**
     * 主动淘汰缓存（写/删除命令，保证强一致与失效）
     */
    void evictBrandCache(Long brandId);
}
