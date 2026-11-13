<template>
  <article class="contest-card" :class="[statusClass, { 'contest-card--compact': compact }]">
    <div v-if="!compact" class="contest-date" aria-hidden="true">
      <i class="el-icon-date"></i>
      <strong>{{ startDate }}</strong>
      <span>{{ startYear }}</span>
    </div>
    <div class="contest-card-main">
      <div class="contest-card-labels">
        <span class="contest-card-status">
          <i :class="statusIcon" aria-hidden="true"></i>{{ $t('m.' + statusName) }}
        </span>
        <template v-if="compact">{{ contest.type | parseContestType }}</template>
        <el-tooltip v-else-if="contest.type === 1" :content="oiScoringRule" placement="top">
          <button v-if="$listeners.rule" type="button" class="contest-rule contest-card-action" @click="$emit('rule', contest.type)">OI</button>
          <span v-else class="contest-rule contest-card-action">OI</span>
        </el-tooltip>
        <button v-else-if="$listeners.rule" type="button" class="contest-rule contest-card-action" @click="$emit('rule', contest.type)">ACM</button>
        <span v-else class="contest-rule">ACM</span>
        <el-tooltip v-if="!compact && access" :content="$t('m.' + access.tips)" placement="top">
          <span class="contest-access contest-card-action">
            <i v-if="contest.auth !== 0" class="el-icon-lock" aria-hidden="true"></i>{{ $t('m.' + access.name) }}
          </span>
        </el-tooltip>
      </div>
      <h3>
        <a class="contest-card-link" :href="$router.resolve(contestRoute).href"
          @click="openContest">{{ contest.title }}</a>
      </h3>
      <div class="contest-card-meta">
        <span><i class="el-icon-time" aria-hidden="true"></i>{{ contest.startTime | localtime(compact ? 'MM-DD HH:mm' : 'YYYY-MM-DD HH:mm') }}</span>
        <span v-if="!compact && duration">{{ duration }}</span>
        <span v-if="!compact && contest.count != null"><i class="el-icon-user" aria-hidden="true"></i>{{ contest.count }}</span>
      </div>
    </div>
    <el-tooltip v-if="!compact && contest.openRank" :content="$t('m.Contest_Outside_ScoreBoard')" placement="top">
      <span class="contest-card-action">
        <el-button circle size="small" icon="el-icon-data-analysis"
          :aria-label="$t('m.Contest_Outside_ScoreBoard')" :disabled="status === CONTEST_STATUS.SCHEDULED"
          @click="$router.push(scoreboardRoute)" />
      </span>
    </el-tooltip>
    <i class="el-icon-arrow-right contest-card-arrow" aria-hidden="true"></i>
  </article>
</template>

<script>
import { CONTEST_STATUS, CONTEST_STATUS_REVERSE, CONTEST_TYPE_REVERSE } from '@/common/constants';
import myMessage from '@/common/message';
import time from '@/common/time';

const statusAppearance = {
  [CONTEST_STATUS.SCHEDULED]: { className: 'is-scheduled', icon: 'el-icon-time' },
  [CONTEST_STATUS.RUNNING]: { className: 'is-running', icon: 'el-icon-video-play' },
  [CONTEST_STATUS.ENDED]: { className: 'is-ended', icon: 'el-icon-circle-check' },
};

export default {
  props: {
    contest: { type: Object, required: true },
    compact: Boolean,
  },
  data: () => ({ CONTEST_STATUS }),
  computed: {
    status() { return Number(this.contest.status); },
    statusName() { return CONTEST_STATUS_REVERSE[this.status].name; },
    statusClass() { return statusAppearance[this.status].className; },
    statusIcon() { return statusAppearance[this.status].icon; },
    access() { return CONTEST_TYPE_REVERSE[this.contest.auth]; },
    contestRoute() { return { name: 'ContestDetails', params: { contestID: this.contest.id } }; },
    scoreboardRoute() {
      return { name: this.contest.type === 0 ? 'ACMScoreBoard' : 'OIScoreBoard',
        params: { contestID: this.contest.id } };
    },
    startDate() { return time.utcToLocal(this.contest.startTime, 'MM-DD'); },
    startYear() { return time.utcToLocal(this.contest.startTime, 'YYYY'); },
    duration() { return time.formatSpecificDuration(this.contest.startTime, this.contest.endTime); },
    oiScoringRule() {
      const rule = this.contest.oiRankScoreType === 'Recent'
        ? 'Based_on_The_Recent_Score_Submitted_Of_Each_Problem'
        : 'Based_on_The_Highest_Score_Submitted_For_Each_Problem';
      return this.$t('m.Contest_Rank') + ' · ' + this.$t('m.' + rule);
    },
  },
  methods: {
    openContest(event) {
      if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey ||
          event.button !== 0) return;
      event.preventDefault();
      if (!this.$store.getters.isAuthenticated) {
        myMessage.warning(this.$t('m.Please_login_first'));
        this.$store.dispatch('changeModalStatus', { visible: true });
        return;
      }
      this.$router.push(this.contestRoute);
    },
  },
};
</script>

<style scoped>
.contest-card {
  --status-ink: #495d74;
  --status-bg: #edf1f5;
  --status-edge: #d8e1ea;
  position: relative;
  display: flex;
  align-items: center;
  gap: 22px;
  padding: 19px 24px;
  border: 1px solid #e0e8ef;
  border-inline-start: 4px solid var(--status-edge);
  border-radius: 14px;
  background: #fff;
  color: #354d63;
  transition: border-color .18s, box-shadow .18s;
}
.contest-card.is-running { --status-ink: #166746; --status-bg: #e5f6ed; --status-edge: #57ab80; }
.contest-card.is-scheduled { --status-ink: #84550b; --status-bg: #fff3d8; --status-edge: #dfb66c; }
.contest-card:hover { border-color: var(--status-edge); box-shadow: 0 5px 18px rgba(60, 105, 145, .10); }
.contest-date {
  flex: 0 0 75px;
  display: grid;
  justify-items: center;
  gap: 3px;
  padding: 10px 5px;
  border-radius: 10px;
  background: var(--status-bg);
  color: var(--status-ink);
}
.contest-date strong { font-size: 20px; font-variant-numeric: tabular-nums; }
.contest-date span { font-size: 12px; }
.contest-card-main { flex: 1; min-width: 0; }
.contest-card-labels, .contest-card-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 12px; }
.contest-card-labels, .contest-card-meta { font-size: 13px; color: #536b82; }
.contest-card-status, .contest-rule, .contest-access { display: inline-flex; align-items: center; gap: 5px; line-height: 1.5; }
.contest-card-status { padding: 3px 9px; border-radius: 999px; color: var(--status-ink); background: var(--status-bg); font-weight: 700; }
.contest-rule { padding: 2px 7px; border: 1px solid #dce7f0; border-radius: 6px; color: #3d6181; background: #f6faff; font: inherit; }
button.contest-rule { cursor: pointer; }
button.contest-rule:hover { background: #eaf3fb; }
.contest-card h3 { margin: 6px 0; font-size: 18px; line-height: 1.45; overflow-wrap: anywhere; }
.contest-card-link { color: inherit; text-decoration: none; }
.contest-card-link::after { content: ''; position: absolute; inset: 0; border-radius: 13px; }
.contest-card-link:focus-visible { outline: none; }
.contest-card-link:focus-visible::after { outline: 2px solid #307bc0; outline-offset: 3px; }
.contest-card-action { position: relative; z-index: 1; }
.contest-card-meta i { margin-inline-end: 4px; }
.contest-card-arrow { color: #7b92a5; }
.contest-card--compact { gap: 8px; padding: 11px 12px; border-radius: 11px; }
.contest-card--compact h3 { font-size: 14px; }
.contest-card--compact .contest-card-labels, .contest-card--compact .contest-card-meta { font-size: 12px; }
@media (max-width: 600px) {
  .contest-card:not(.contest-card--compact) { gap: 10px; padding: 12px; }
  .contest-date { flex-basis: 51px; }
  .contest-date strong { font-size: 15px; }
  .contest-card h3 { font-size: 16px; }
  .contest-card-arrow { display: none; }
}
@media (prefers-reduced-motion: reduce) { .contest-card { transition: none; } }
</style>
