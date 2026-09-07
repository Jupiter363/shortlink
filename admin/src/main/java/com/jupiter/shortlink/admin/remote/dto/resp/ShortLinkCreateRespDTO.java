package com.jupiter.shortlink.admin.remote.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortLinkCreateRespDTO {
    private Long linkId;
    private Long routeVersion;
    private Long targetRevision;
    private String shortUri;

    /** 分组标识 */
    private String gid;

    /** 原始链接 */
    private String originUrl;

    /** 短链接 */
    private String fullShortUrl;
}
