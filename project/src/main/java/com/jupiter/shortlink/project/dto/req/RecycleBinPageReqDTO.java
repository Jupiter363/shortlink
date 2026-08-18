package com.jupiter.shortlink.project.dto.req;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jupiter.shortlink.project.dao.entity.ShortLinkDO;
import lombok.Data;

import java.util.List;

/**
 * 短链接分页回收站请求参数
 */
@Data
public class RecycleBinPageReqDTO extends Page<ShortLinkDO> {

    /**
     * gid列表
     */
    private List<String> gidList;
}
