package com.jupiter.shortlink.admin.remote;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.admin.common.convention.result.Result;
import com.jupiter.shortlink.admin.remote.dto.resp.*;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Complete wire shapes frozen from Command controllers; strict production decoding must survive a
 * committed write.
 */
class CommandResponseDecodingTest {
    private final ObjectMapper json =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final String CREATED =
            """
{"code":"0","data":{"linkId":9007199254740999,"fullShortUrl":"nurl.ink/abc",
"originUrl":"https://example.test","gid":"g1","shortUri":"abc","routeVersion":3000000000,"targetRevision":1},"success":true}
""";

    @Test
    void committedCreateWrapperDecodesDerivedSuccessShortUriAndLongIdentity() throws Exception {
        Result<ShortLinkCreateRespDTO> result = json.readValue(CREATED, new TypeReference<>() {});
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().getLinkId()).isEqualTo(9_007_199_254_740_999L);
        assertThat(result.getData().getShortUri()).isEqualTo("abc");
        assertThat(result.getData().getRouteVersion()).isEqualTo(3_000_000_000L);
    }

    @Test
    void computedSuccessCannotOverrideAuthoritativeResultCode() throws Exception {
        Result<Object> result =
                json.readValue(
                        "{\"code\":\"FORBIDDEN\",\"data\":null,\"success\":true}",
                        new TypeReference<>() {});
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void synchronousBatchWrapperPreservesEveryCreatedLinkIdentity() throws Exception {
        Result<ShortLinkBatchCreateRespDTO> result =
                json.readValue(
                        """
{"code":"0","data":{"total":2,"baseLinkInfos":[{"linkId":9007199254740999,"fullShortUrl":"nurl.ink/abc",
"originUrl":"https://example.test","describe":"fixture"}],"jobId":null,"state":"SUCCEEDED","resultUrl":null}}
""",
                        new TypeReference<>() {});
        assertThat(result.getData().getTotal()).isEqualTo(2L);
        assertThat(result.getData().getBaseLinkInfos().get(0).getLinkId())
                .isEqualTo(9_007_199_254_740_999L);
    }

    @Test
    void asynchronousBatchAcceptanceRetainsNullTotalAndJobState() throws Exception {
        Result<ShortLinkBatchCreateRespDTO> result =
                json.readValue(
                        """
{"code":"0","data":{"total":null,"baseLinkInfos":[],"jobId":"job-1","state":"QUEUED","resultUrl":"/internal/command/batches/job-1/rows"}}
""",
                        new TypeReference<>() {});
        assertThat(result.getData().getTotal()).isNull();
        assertThat(result.getData().getJobId()).isEqualTo("job-1");
        assertThat(result.getData().getState()).isEqualTo("QUEUED");
    }

    @Test
    void batchStatusAndRowCreatedResultKeepLongCountsAndIdentity() throws Exception {
        var status =
                json.readValue(
                        """
{"jobId":"job-1","state":"SUCCEEDED","totalRows":3000000000,"validRows":3000000000,"invalidRows":0,
"succeededRows":3000000000,"failedRows":0,"error":null,"checksum":"abc","actualBytes":9007199254740999}
""",
                        BatchCommandRemoteService.Status.class);
        List<BatchCommandRemoteService.Row> rows =
                json.readValue(
                        """
[{"row":3000000000,"state":"SUCCEEDED","linkId":9007199254740999,"result":{"linkId":9007199254740999,
"fullShortUrl":"nurl.ink/abc","originUrl":"https://example.test","gid":"g1","shortUri":"abc","routeVersion":1,"targetRevision":1},"error":null}]
""",
                        new TypeReference<>() {});
        assertThat(status.totalRows()).isEqualTo(3_000_000_000L);
        assertThat(status.actualBytes()).isEqualTo(9_007_199_254_740_999L);
        assertThat(rows.get(0).row()).isEqualTo(3_000_000_000L);
        assertThat(rows.get(0).linkId()).isEqualTo(9_007_199_254_740_999L);
    }

    @Test
    void pageWrapperDecodesCurrentMetadataRevisionsAndNullStatistics() throws Exception {
        Result<Page<ShortLinkPageRespDTO>> result =
                json.readValue(
                        """
{"code":"0","data":{"records":[{"linkId":9007199254740999,"id":9007199254740999,"gid":"g1",
"domain":"nurl.ink","shortUri":"abc","fullShortUrl":"nurl.ink/abc","originUrl":"https://example.test",
"routeVersion":3000000000,"targetRevision":2,"ownershipVersion":3,"enableStatus":0,"title":null,"favicon":null,
"metadataStatus":"PENDING","validDateType":0,"validDate":null,"createTime":"2026-07-01 10:00:00","describe":"fixture",
"todayPv":null,"todayUv":null,"todayUip":null,"totalPv":null,"totalUv":null,"totalUip":null}],
"current":1,"size":20,"total":3000000000},"success":true}
""",
                        new TypeReference<>() {});
        assertThat(result.getData().getTotal()).isEqualTo(3_000_000_000L);
        assertThat(result.getData().getRecords().get(0).getTodayPv()).isNull();
        assertThat(result.getData().getRecords().get(0).getMetadataStatus()).isEqualTo("PENDING");
    }

    @Test
    void unrelatedUnknownResponseFieldsStillFailStrictDecoding() {
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                        CREATED.replace(
                                                "\"shortUri\":\"abc\"",
                                                "\"unexpectedField\":true,\"shortUri\":\"abc\""),
                                        new TypeReference<Result<ShortLinkCreateRespDTO>>() {}))
                .hasMessageContaining("unexpectedField");
    }
}
