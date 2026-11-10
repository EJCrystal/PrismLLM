package dev.soven.prismllm.routing.domain.candidate

/**
 * 一次目录快照下解析模型的可用性。路由与「为什么不可用」的诊断判定都基于同一份快照，
 * 避免候选查询与诊断查询之间凭据恢复（临时停用到期、人工启用）导致自相矛盾的结论。
 *
 * @param candidates        当前协议下可用的路由候选（含协议转换）。
 * @param configured        全协议下模型已配置的渠道条目，与凭据是否可路由无关。
 * @param protocolReachable 是否存在任一渠道能原生支持或转换出当前协议。
 * @param retryAt           凭据全部不可路由时的最早自动恢复时间；没有临时停用密钥时为 null。
 * @author 文杰
 * @since 2026-11-09
 */
data class CandidateSet(
    val candidates: List<RouteCandidate>,
    val configured: Boolean,
    val protocolReachable: Boolean,
    val retryAt: Long?,
)