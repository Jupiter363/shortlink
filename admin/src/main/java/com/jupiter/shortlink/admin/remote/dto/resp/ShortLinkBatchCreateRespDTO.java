package com.jupiter.shortlink.admin.remote.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 短链接批量创建响应对象 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortLinkBatchCreateRespDTO {
    private String jobId;
    private String state;
    private String resultUrl;

    /** 成功数量 */
    private Long total;

    /** 批量创建返回参数 */
    private List<ShortLinkBaseInfoRespDTO> baseLinkInfos;
}
