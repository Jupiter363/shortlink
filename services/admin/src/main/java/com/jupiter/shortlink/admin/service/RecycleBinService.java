package com.jupiter.shortlink.admin.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jupiter.shortlink.admin.common.convention.result.Result;
import com.jupiter.shortlink.admin.remote.dto.req.ShortLinkRecycleBinPageReqDTO;
import com.jupiter.shortlink.admin.remote.dto.resp.ShortLinkPageRespDTO;

/**
 * Url回收站接口层
 */
public interface RecycleBinService {

    /**
     * 分页查询回收站短链接
     * @param requestParam 请求参数
     * @return 返回参数
     */
    Result<Page<ShortLinkPageRespDTO>>  pageRecycleBinShortLink(ShortLinkRecycleBinPageReqDTO requestParam);
}
