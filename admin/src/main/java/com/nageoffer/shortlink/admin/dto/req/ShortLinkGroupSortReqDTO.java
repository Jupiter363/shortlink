package com.nageoffer.shortlink.admin.dto.req;

import lombok.Data;

/**
 * 短链接分组排序请求参数
 */
@Data
public class ShortLinkGroupSortReqDTO {
    /**
     * 分组id
     */
    private String gid;

    /**
     * 排序参数sortOrder
     */
    private Integer sortOrder;
}
