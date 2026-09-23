import { request } from './http.js'
import { buildChatBody } from '../domain/agentModel.js'
import { buildReviewBody, buildDisableBody } from '../domain/riskModel.js'

const base = '/api/short-link/admin/v1'
const segment = (value) => encodeURIComponent(String(value))
const reportPath = (input) =>
  `${base}/agent/campaign/reports/${segment(input.reportId)}/revisions/${segment(input.revision)}`
const reportQuery = (input) => ({
  sessionId: input.sessionId,
  runId: input.runId,
  planId: input.planId,
  planRevision: input.planRevision
})

export const agentApi = {
  health: (signal) => request(`${base}/agent/health`, { signal, timeoutMs: 15000 }),
  chat: (input, signal) =>
    request(`${base}/agent/chat`, {
      method: 'POST',
      body: buildChatBody(input),
      signal,
      timeoutMs: 60000
    }),
  progress: (input, signal) =>
    request(`${base}/agent/campaign/runs/${segment(input.runId)}/progress`, {
      query: { sessionId: input.sessionId, requestId: input.requestId },
      signal,
      timeoutMs: 20000
    }),
  report: (input, signal) => request(reportPath(input), { query: reportQuery(input), signal }),
  reportRows: (input, signal) =>
    request(`${reportPath(input)}/blocks/${segment(input.blockId)}/rows`, {
      query: { ...reportQuery(input), cursor: input.cursor, size: input.size || 25 },
      signal
    }),
  reportExport: (input, signal) =>
    request(`${reportPath(input)}/export`, { query: reportQuery(input), signal, timeoutMs: 30000 }),
  reportHistory: (input, signal) =>
    request(`${base}/agent/campaign/sessions/${segment(input.sessionId)}/reports`, {
      query: { cursor: input.cursor, size: input.size || 20 },
      signal
    })
}

export const riskApi = {
  overview: (gid, signal) => request(`${base}/risk/groups/${segment(gid)}/overview`, { signal }),
  cards: (gid, signal) => request(`${base}/risk/groups/${segment(gid)}/short-links`, { signal }),
  detail: (card, signal) =>
    request(`${base}/risk/short-links`, {
      query: { gid: card.gid, domain: card.domain, shortUri: card.shortUri },
      signal
    }),
  events: (query, signal) =>
    request(`${base}/risk/events`, {
      query: {
        gid: query.gid,
        targetType: query.targetType,
        domain: query.domain,
        shortUri: query.shortUri,
        pageNo: query.pageNo || 1,
        pageSize: 10
      },
      signal
    }),
  policies: (linkId, cursor, signal) =>
    request(`${base}/risk/current-policies`, {
      query: { linkId, cursor: cursor || undefined },
      signal
    }),
  review: (input) =>
    request(`${base}/risk/reviews`, { method: 'POST', body: buildReviewBody(input) }),
  disable: (policyId, input) =>
    request(`${base}/risk/policies/${segment(policyId)}/disable`, {
      method: 'POST',
      body: buildDisableBody(input),
      timeoutMs: 20000
    }),
  command: (commandId) =>
    request(`${base}/risk/commands/${segment(commandId)}`, { timeoutMs: 15000 })
}
