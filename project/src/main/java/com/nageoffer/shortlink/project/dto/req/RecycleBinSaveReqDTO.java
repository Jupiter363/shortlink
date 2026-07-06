package com.nageoffer.shortlink.project.dto.req;

import lombok.Data;

/**
 * 回收站保存请求参数
 */
@Data
public class RecycleBinSaveReqDTO {

    /**
     * 分组标识
     */
    private String gid;

    /**
     * 全部短链接
     */
    private String fullShortUrl;
}
