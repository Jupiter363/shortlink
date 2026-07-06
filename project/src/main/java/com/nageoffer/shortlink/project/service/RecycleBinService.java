package com.nageoffer.shortlink.project.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.nageoffer.shortlink.project.dao.entity.ShortLinkDO;
import com.nageoffer.shortlink.project.dto.req.RecycleBinPageReqDTO;
import com.nageoffer.shortlink.project.dto.req.RecycleBinRecoverReqDTO;
import com.nageoffer.shortlink.project.dto.req.RecycleBinSaveReqDTO;
import com.nageoffer.shortlink.project.dto.resp.ShortLinkPageRespDTO;

/**
 * 回收站管理接口层
 */
public interface RecycleBinService extends IService<ShortLinkDO> {

    /**
     * 保存回收站
     * @param requestParam 保存回收站请求参数
     */
    void saveRecycleBin(RecycleBinSaveReqDTO requestParam);

    /**
     * 分页查询回收站
     * @param requestParam 分页查询请求参数
     * @return 分页查询结果
     */
    IPage<ShortLinkPageRespDTO> pageShortLink(RecycleBinPageReqDTO requestParam);

    /**
     * 回收站恢复链接
     * @param requestParam 回收站恢复链接请求参数
     */
    void recoverRecycleBin(RecycleBinRecoverReqDTO requestParam);

    /**
     * 回收站删除链接
     * @param requestParam 回收站恢复链接请求参数
     */
    void removeRecycleBin(RecycleBinRecoverReqDTO requestParam);
}
