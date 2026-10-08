import type { Account, ReplyEligibility, SendRoute } from './types';

const FOUR_HOURS_MS = 4 * 60 * 60 * 1000;
const TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000;

export function evaluateReplyEligibility(account: Account, route: SendRoute, now: string, lastQualifyingInboundAt?: string): ReplyEligibility {
  if (account.capabilities.qualificationStatus === 'disabled') {
    return { windowState: 'unknown', route, eligibleNow: false, reasonCode: 'connector_disabled', policyVersion: account.capabilities.policyVersion, needsRecheck: true };
  }
  if (!account.capabilities.sendRoutes.includes(route)) {
    return {
      windowState: 'unknown',
      route,
      eligibleNow: false,
      reasonCode: 'route_not_supported_by_capability_snapshot',
      policyVersion: account.capabilities.policyVersion,
      needsRecheck: true
    };
  }

  if (route === 'native_handoff') {
    return {
      windowState: 'not_applicable',
      route,
      eligibleNow: true,
      reasonCode: 'source_app_handoff_user_completes_action',
      policyVersion: account.capabilities.policyVersion,
      needsRecheck: false
    };
  }

  if (route !== 'api' || !account.grants.includes('send')) {
    return { windowState: 'unknown', route, eligibleNow: false, reasonCode: 'send_grant_missing', policyVersion: account.capabilities.policyVersion, needsRecheck: true };
  }

  if (account.provider === 'gmail') {
    return {
      windowState: 'not_applicable',
      route,
      eligibleNow: account.grants.includes('send'),
      reasonCode: account.grants.includes('send') ? 'gmail_send_grant_present' : 'gmail_send_grant_missing',
      policyVersion: account.capabilities.policyVersion,
      needsRecheck: true
    };
  }

  if (account.provider !== 'instagram' && account.provider !== 'facebook' && account.provider !== 'whatsapp' && account.provider !== 'teams') {
    return { windowState: 'not_applicable', route, eligibleNow: true, reasonCode: 'provider_route_available', policyVersion: account.capabilities.policyVersion, needsRecheck: true };
  }

  if (!lastQualifyingInboundAt || !Number.isFinite(Date.parse(now)) || !Number.isFinite(Date.parse(lastQualifyingInboundAt)) || Date.parse(lastQualifyingInboundAt) > Date.parse(now)) {
    return {
      windowState: 'unknown',
      route,
      eligibleNow: false,
      reasonCode: 'missing_qualifying_inbound_event',
      policyVersion: account.capabilities.policyVersion,
      needsRecheck: true
    };
  }

  const nowMs = Date.parse(now);
  const closesAtMs = Date.parse(lastQualifyingInboundAt) + TWENTY_FOUR_HOURS_MS;
  const closesAt = new Date(closesAtMs).toISOString();
  const remainingMs = closesAtMs - nowMs;

  if (remainingMs <= 0) {
    return {
      windowState: 'expired',
      closesAt,
      route,
      eligibleNow: false,
      reasonCode: 'customer_reply_window_expired',
      policyVersion: account.capabilities.policyVersion,
      needsRecheck: true
    };
  }

  return {
    windowState: remainingMs <= FOUR_HOURS_MS ? 'expiring' : 'active',
    closesAt,
    route,
    eligibleNow: true,
    reasonCode: remainingMs <= FOUR_HOURS_MS ? 'reply_window_expiring' : 'reply_window_active',
    policyVersion: account.capabilities.policyVersion,
    needsRecheck: true
  };
}
