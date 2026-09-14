import { request } from './http.js'
import { buildChatBody } from '../domain/agentModel.js'
import { buildReviewBody, buildDisableBody } from '../domain/riskModel.js'

const base = '/api/short-link/admin/v1'
const segment = (value) => encodeURIComponent(String(value))

export const agentApi = {
  health: (signal) => request(`${base}/agent/health`, { signal, timeoutMs: 15000 }),
  chat: (input, signal) =>
    request(`${base}/agent/chat`, {
      method: 'POST',
      body: buildChatBody(input),
      signal,
      timeoutMs: 60000
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
