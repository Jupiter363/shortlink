package com.nageoffer.shortlink.admin.remote.dto.req;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;

import java.util.List;

/**
 * 短链接分页回收站请求参数
 */
@Data
public class ShortLinkRecycleBinPageReqDTO extends Page {

    /**
     * gid列表
     */
    private List<String> gidList;
}
