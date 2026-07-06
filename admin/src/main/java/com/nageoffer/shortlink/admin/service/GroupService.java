package com.nageoffer.shortlink.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.nageoffer.shortlink.admin.dao.entity.GroupDO;
import com.nageoffer.shortlink.admin.dto.req.ShortLinkGroupSortReqDTO;
import com.nageoffer.shortlink.admin.dto.req.ShortLinkGroupUpdateReqDTO;
import com.nageoffer.shortlink.admin.dto.resp.ShortLinkGroupRespDTO;

import java.util.List;

/**
 * 短链接分组接口层
 */
public interface GroupService extends IService<GroupDO> {
    /**
     * 新增短链接分组
     * @param groupName 短链接分组名称
     */
    void saveGroup(String groupName);

    /**
     * 新增短链接分组
     * @param username 用户名
     * @param groupName 短链接分组名称
     */
    void saveGroup(String username,String groupName);

    /**
     * 查询短链接分组集合
     * @return 短链接分组集合
     */
    List<ShortLinkGroupRespDTO> listGroup();

    /**
     * 修改短链接分组名
     * @param requestParam 请求参数
     */
    void updateGroup(ShortLinkGroupUpdateReqDTO requestParam);

    /**
     * 删除短链接分组名
     * @param  gid 请求参数
     */
    void deleteGroup(String gid);

    /**
     * 短链接分组排序
     * @param requestParam 排序请求参数
     */
    void sortGroup(List<ShortLinkGroupSortReqDTO> requestParam);
}
