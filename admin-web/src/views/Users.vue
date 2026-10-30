<template>
  <div class="page-container">
    <div class="page-head">
      <div>
        <h2>用户管理</h2>
        <div class="sub">按注册时间倒序 · 服务端分页 · 共 {{ stats.total }} 个账号</div>
      </div>
      <div class="head-actions">
        <a-button :loading="loading" @click="load">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
      </div>
    </div>

    <div class="stat-chips">
      <div class="stat-chip">
        <span class="chip-label">总用户</span>
        <span class="chip-value">{{ formatNumber(stats.total) }}</span>
      </div>
      <div class="stat-chip">
        <span class="chip-label">管理员</span>
        <span class="chip-value">{{ formatNumber(stats.admin) }}</span>
      </div>
      <div class="stat-chip">
        <span class="chip-label">已禁用</span>
        <span class="chip-value">{{ formatNumber(stats.disabled) }}</span>
      </div>
      <div class="stat-chip">
        <span class="chip-label">在线设备</span>
        <span class="chip-value">{{ formatNumber(stats.active) }}</span>
      </div>
    </div>

    <a-alert
      v-if="emailSupported !== true"
      :type="emailSupported === false ? 'warning' : 'info'"
      show-icon
      class="mb16"
      :message="emailSupported === false
        ? '邮箱字段未就绪：列表接口未返回 email'
        : '邮箱接口状态待确认'"
    >
      <template #description>
        <div>
          当前列表接口
          <template v-if="emailSupported === false">不返回 <code>email</code> 字段</template>
          <template v-else>可能不返回 <code>email</code> 字段</template>
          ，因此「重置密码 / 修改邮箱」暂不可用。后端
          <code>POST /admin/users/{id}/reset-password</code> 与
          <code>PUT /admin/users/{id}/email</code> 可用后，本页刷新即可自动启用。
        </div>
      </template>
    </a-alert>
    <a-alert
      v-if="loadError"
      type="error"
      show-icon
      class="mb16"
      closable
      :message="loadError"
      @close="loadError = ''"
    >
      <template #description>这是后端返回的原始错误，未被改写。</template>
      <template #action>
        <a-button size="small" @click="load">重试</a-button>
      </template>
    </a-alert>

    <div class="content-card table-card">
      <div class="table-toolbar">
        <div class="search-group">
          <a-input
            v-model:value="keyword"
            placeholder="搜索用户名 / 昵称"
            aria-label="按用户名或昵称搜索用户"
            allow-clear
            style="width: 320px"
            @press-enter="onSearch"
          >
            <template #suffix><SearchOutlined /></template>
          </a-input>
          <a-button aria-label="搜索用户" :loading="loading" @click="onSearch">
            <template #icon><SearchOutlined /></template>
          </a-button>
        </div>
        <span class="grow" />
        <span class="muted small">共 {{ stats.total }} 个用户 · 第 {{ page }} 页</span>
      </div>

      <a-table
        :columns="columns"
        :data-source="items"
        :loading="loading"
        row-key="id"
        :pagination="false"
        :scroll="{ x: 1088 }"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'user'">
            <div class="user-cell">
              <a-avatar :src="record.avatarUrl || undefined" :size="36">
                {{ (record.nickname || record.username || '?').slice(0, 1).toUpperCase() }}
              </a-avatar>
              <div class="user-meta">
                <div class="cell-main">
                  {{ record.nickname || record.username }}
                  <a-tag v-if="isSelf(record as AdminUser)" color="orange">我</a-tag>
                </div>
                <div class="cell-sub">{{ record.username }}</div>
                <a-tooltip :title="`点击复制用户 ID：${record.id}`">
                  <div class="cell-sub mono id-sub" @click="copyId(record.id as string)">{{ record.id }}</div>
                </a-tooltip>
              </div>
            </div>
          </template>

          <template v-else-if="column.key === 'email'">
            <span v-if="record.email" class="mono">{{ record.email }}</span>
            <a-tooltip
              v-else
              :title="emailSupported === false
                ? '当前用户列表接口未返回 email 字段，无法判断该用户是否已绑定邮箱'
                : '该用户未绑定邮箱'"
            >
              <a-tag :color="emailSupported === false ? 'default' : 'orange'">
                {{ emailSupported === false ? '未上线' : '未绑定' }}
              </a-tag>
            </a-tooltip>
          </template>

          <template v-else-if="column.key === 'status'">
            <a-tag :color="userStatusLabel(record.status).color">{{ userStatusLabel(record.status).label }}</a-tag>
          </template>

          <template v-else-if="column.key === 'role'">
            <a-tag v-if="record.isAdmin" color="gold">管理员</a-tag>
            <a-tag v-else>普通用户</a-tag>
          </template>

          <template v-else-if="column.key === 'plan'">
            <div class="plan-cell">
              <a-tag :color="planTagColor(record.planCode, record.planExpiresAt)">
                {{ displayPlanName(record.planName, record.planCode) }}
              </a-tag>
              <span v-if="isPlanExpired(record.planExpiresAt)" class="cell-sub">
                <a-tag color="red">已过期</a-tag>
              </span>
            </div>
            <div class="cell-sub nowrap">
              <template v-if="record.planExpiresAt">
                {{ formatDateTime(record.planExpiresAt) }} · {{ untilNow(record.planExpiresAt) }}
              </template>
              <template v-else>永久</template>
            </div>
          </template>

          <template v-else-if="column.key === 'counts'">
            <a-space :size="6">
              <a-tag color="default">设备 {{ record.deviceCount ?? 0 }}</a-tag>
              <a-tag color="default">传输 {{ record.transferCount ?? 0 }}</a-tag>
            </a-space>
          </template>

          <template v-else-if="column.key === 'createdAt'">
            <span class="nowrap">{{ formatDateTime(record.createdAt) }}</span>
            <div class="cell-sub nowrap">{{ fromNow(record.createdAt) }}</div>
          </template>

          <template v-else-if="column.key === 'actions'">
            <div class="row-actions">
              <span class="action-wrap">
                <a-tooltip :title="resetTooltip(record as AdminUser)">
                  <a-button
                    type="link"
                    size="small"
                    :disabled="!canReset(record as AdminUser)"
                    :loading="busyId === record.id"
                    @click="resetPassword(record as AdminUser)"
                  >
                    重置密码
                  </a-button>
                </a-tooltip>
              </span>

              <span class="action-wrap">
                <a-tooltip :title="emailTooltip(record as AdminUser)">
                  <a-button
                    type="link"
                    size="small"
                    :disabled="!canEditEmail(record as AdminUser)"
                    @click="openEmail(record as AdminUser)"
                  >
                    修改邮箱
                  </a-button>
                </a-tooltip>
              </span>

              <a-dropdown placement="bottomRight" :trigger="['click']">
                <a-button type="link" size="small">
                  更多 <DownOutlined />
                </a-button>
                <template #overlay>
                  <a-menu>
                    <a-menu-item key="detail" @click="openDetail(record as AdminUser)">
                      <ProfileOutlined />
                      <span class="menu-text">详情</span>
                    </a-menu-item>
                    <a-menu-item key="status" @click="toggleStatus(record as AdminUser)">
                      <StopOutlined v-if="record.status === 1" />
                      <CheckCircleOutlined v-else />
                      <span class="menu-text">{{ record.status === 1 ? '禁用账号' : '启用账号' }}</span>
                    </a-menu-item>
                    <a-menu-item
                      key="admin"
                      :disabled="isSelf(record as AdminUser)"
                      @click="toggleAdmin(record as AdminUser)"
                    >
                      <TeamOutlined />
                      <span class="menu-text">{{ record.isAdmin ? '取消管理员' : '设为管理员' }}</span>
                    </a-menu-item>
                    <a-menu-item key="plan" @click="openPlan(record as AdminUser)">
                      <CrownOutlined />
                      <span class="menu-text">调整档位</span>
                    </a-menu-item>
                    <a-menu-item key="grant" @click="openGrant(record as AdminUser)">
                      <GiftOutlined />
                      <span class="menu-text">加量 / 开白</span>
                    </a-menu-item>
                    <a-menu-divider />
                    <a-menu-item key="delete" danger :disabled="isSelf(record as AdminUser)" @click="openDelete(record as AdminUser)">
                      <DeleteOutlined />
                      <span class="menu-text">删除用户</span>
                    </a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </div>
          </template>
        </template>

        <template #emptyText>
          <a-empty description="没有匹配的用户">
            <template #description>
              <div class="cell-main">没有匹配的用户</div>
              <div class="cell-sub">试试清空搜索词；用户列表按注册时间倒序展示</div>
            </template>
          </a-empty>
        </template>
      </a-table>

      <div class="table-foot">
        <span class="muted small">本页 {{ items.length }} 条 · 接口无 total，按「满页」推算是否存在下一页</span>
        <a-pagination
          :current="page"
          :page-size="pageSize"
          :total="paginationTotal"
          :show-size-changer="true"
          :page-size-options="['10', '20', '50']"
          @change="onPageChange"
          @showSizeChange="onSizeChange"
        />
      </div>
    </div>

    <!-- 详情抽屉（task-122：五区块各自独立加载/错误态） -->
    <a-drawer
      v-model:open="detailOpen"
      :title="detail ? `用户详情 · ${detail.username}` : '用户详情'"
      :width="480"
      placement="right"
      :mask-closable="true"
      class="user-detail-drawer"
    >
      <template v-if="detail">
        <section class="dd-section">
          <div class="dd-title">基础信息</div>
          <a-descriptions :column="1" bordered size="small">
            <a-descriptions-item label="用户 ID"><span class="mono">{{ detail.id }}</span></a-descriptions-item>
            <a-descriptions-item label="用户名">{{ detail.username }}</a-descriptions-item>
            <a-descriptions-item label="昵称">{{ detail.nickname || '—' }}</a-descriptions-item>
            <a-descriptions-item label="邮箱">
              <span v-if="detail.email" class="mono">{{ detail.email }}</span>
              <a-tag v-else :color="emailSupported === false ? 'default' : 'orange'">
                {{ emailSupported === false ? '未上线' : '未绑定' }}
              </a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="状态">
              <a-tag :color="userStatusLabel(detail.status).color">{{ userStatusLabel(detail.status).label }}</a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="角色">
              <a-tag :color="detail.isAdmin ? 'gold' : 'default'">{{ detail.isAdmin ? '管理员' : '普通用户' }}</a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="档位">
              <template v-if="detail.planCode || detail.planName">
                <a-tag :color="planTagColor(detail.planCode, detail.planExpiresAt)">
                  {{ displayPlanName(detail.planName, detail.planCode) }}
                </a-tag>
                <span class="muted small">
                  {{ detail.planExpiresAt ? `${formatDateTime(detail.planExpiresAt)} · ${untilNow(detail.planExpiresAt)}` : '永久' }}
                </span>
                <a-tag v-if="isPlanExpired(detail.planExpiresAt)" color="red">已过期</a-tag>
              </template>
              <span v-else class="muted">接口未返回</span>
            </a-descriptions-item>
            <a-descriptions-item label="设备数">{{ detail.deviceCount ?? 0 }}</a-descriptions-item>
            <a-descriptions-item label="传输数">{{ detail.transferCount ?? 0 }}</a-descriptions-item>
            <a-descriptions-item label="注册时间">{{ formatDateTime(detail.createdAt) }}</a-descriptions-item>
          </a-descriptions>
        </section>

        <!-- 空间 -->
        <section class="dd-section">
          <div class="dd-title">
            空间
            <a-tooltip :title="spacesScopeHint"><QuestionCircleOutlined class="dd-hint-icon" /></a-tooltip>
          </div>
          <a-spin :spinning="spacesLoading">
            <a-alert
              v-if="spacesError"
              type="error"
              show-icon
              class="dd-block-error"
              :message="spacesError"
            >
              <template #action>
                <a-button size="small" type="link" @click="loadDetailSpaces()">重试</a-button>
              </template>
            </a-alert>
            <template v-else>
              <div v-if="detailSpaces.length" class="dd-summary">
                <span>共 {{ detailSpaces.length }} 个空间</span>
                <span class="muted small">{{ usageHeadText }}</span>
              </div>
              <a-progress
                v-if="usagePercentValue !== null"
                :percent="usagePercentValue"
                size="small"
                :show-info="false"
                class="dd-usage-bar"
              />
              <div v-for="w in detailSpaces.slice(0, 5)" :key="w.id" class="dd-space-row">
                <div class="dd-space-line">
                  <span class="dd-ellipsis">{{ workspaceLabel(w) }}</span>
                  <span class="muted small nowrap">
                    {{ formatBytes(w.usedSize) }} / {{ formatBytes(w.maxSize) }}
                  </span>
                </div>
                <a-progress :percent="spacePercent(w)" size="small" :show-info="false" />
              </div>
              <div v-if="detailSpaces.length > 5" class="muted small">
                …仅展示前 5 个，共 {{ detailSpaces.length }} 个
              </div>
              <a-empty v-if="!detailSpaces.length" description="该用户没有工作区" />
              <div class="dd-note">{{ spacesScopeHint }}</div>
            </template>
          </a-spin>
        </section>

        <!-- 最近分享 -->
        <section class="dd-section">
          <div class="dd-title">
            最近分享
            <a-button v-if="detailShares.length" type="link" size="small" class="dd-title-link" @click="goShares">
              查看全部
            </a-button>
          </div>
          <a-spin :spinning="sharesLoading">
            <a-alert
              v-if="sharesError"
              type="error"
              show-icon
              class="dd-block-error"
              :message="sharesError"
            >
              <template #action>
                <a-button size="small" type="link" @click="loadDetailShares">重试</a-button>
              </template>
            </a-alert>
            <template v-else>
              <div v-if="detailShares.length" class="dd-list">
                <div v-for="s in detailShares" :key="s.id" class="dd-list-row">
                  <div class="dd-list-main">
                    <span class="dd-ellipsis">{{ s.fileName }}</span>
                    <a-tag :color="shareTagColor(s)">{{ shareStateText(s) }}</a-tag>
                  </div>
                  <div class="muted small">
                    {{ s.downloadCount }} 次下载 · 创建于 {{ formatDateTime(s.createdAt) }}
                  </div>
                </div>
              </div>
              <a-empty v-else description="该用户暂无分享" />
              <div class="dd-note">
                按用户名「{{ detail.username }}」模糊匹配（后端 ownerKeyword），只取最近 5 条；共 {{ detailSharesTotal }} 条
              </div>
            </template>
          </a-spin>
        </section>

        <!-- 最近审计 -->
        <section class="dd-section">
          <div class="dd-title">最近审计</div>
          <a-spin :spinning="auditsLoading">
            <a-alert
              v-if="auditsError"
              type="error"
              show-icon
              class="dd-block-error"
              :message="auditsError"
            >
              <template #action>
                <a-button size="small" type="link" @click="loadDetailAudits">重试</a-button>
              </template>
            </a-alert>
            <template v-else>
              <div v-if="detailAudits.length" class="dd-list">
                <div v-for="a in detailAudits" :key="a.id" class="dd-list-row">
                  <div class="dd-list-main">
                    <span class="mono dd-ellipsis">{{ a.action }}</span>
                    <a-tag v-if="a.targetType" color="default">{{ auditTargetTypeLabel(a.targetType) }}</a-tag>
                  </div>
                  <div class="muted small">
                    {{ a.actorName || a.actorId || '—' }} · {{ formatDateTime(a.createdAtMillis) }}
                  </div>
                </div>
              </div>
              <a-empty v-else description="最近审计中没有与该用户相关的记录" />
              <div class="dd-note">{{ auditScopeNote }}</div>
            </template>
          </a-spin>
        </section>

        <!-- 快捷操作 -->
        <section class="dd-section">
          <div class="dd-title">操作</div>
          <a-space wrap>
            <a-button @click="openPlan(detail)">调整档位</a-button>
            <a-tooltip :title="resetTooltip(detail)">
              <a-button :disabled="!canReset(detail)" :loading="busyId === detail.id" @click="resetPassword(detail)">
                重置密码
              </a-button>
            </a-tooltip>
            <a-tooltip :title="emailTooltip(detail)">
              <a-button :disabled="!canEditEmail(detail)" @click="openEmail(detail)">修改邮箱</a-button>
            </a-tooltip>
            <a-tooltip :title="detail.status === 1 ? '禁用后该用户所有设备无法继续使用服务' : '启用后该账号可正常登录与传输'">
              <a-button :danger="detail.status === 1" :loading="busyId === detail.id" @click="toggleStatus(detail)">
                {{ detail.status === 1 ? '禁用账号' : '启用账号' }}
              </a-button>
            </a-tooltip>
            <a-button @click="openGrant(detail)">加量 / 开白</a-button>
          </a-space>
          <div class="dd-note">操作成功就地刷新本抽屉与列表行（不整页刷新）；禁用/启用走既有 PUT /api/admin/users/{id}/status。</div>
        </section>
      </template>
      <a-spin v-else :spinning="detailLoading">
        <a-empty description="加载中…" />
      </a-spin>
    </a-drawer>

    <!-- 修改邮箱 -->
    <a-modal
      v-model:open="emailOpen"
      title="修改用户邮箱"
      :confirm-loading="emailSaving"
      ok-text="保存"
      cancel-text="取消"
      @ok="saveEmail"
    >
      <a-form layout="vertical" class="mt8">
        <a-form-item label="用户">
          <span class="mono">{{ target?.username }}（{{ target?.id }}）</span>
        </a-form-item>
        <a-form-item label="新邮箱" :validate-status="emailError ? 'error' : undefined" :help="emailError">
          <a-input v-model:value="emailInput" placeholder="name@example.com" allow-clear />
        </a-form-item>
        <a-alert
          type="info"
          show-icon
          message="修改后该用户登录仍用原密码；若邮箱已被其他账号占用，后端返回 409。"
        />
      </a-form>
    </a-modal>

    <!-- 调整档位 -->
    <a-modal
      v-model:open="planOpen"
      title="调整用户档位"
      :confirm-loading="planSaving"
      ok-text="调整档位"
      cancel-text="取消"
      @ok="savePlan"
    >
      <a-form layout="vertical">
        <a-form-item label="目标用户">
          <span class="mono">{{ target?.username }}（{{ target?.id }}）</span>
        </a-form-item>
        <a-form-item label="当前档位">
          <a-tag :color="planTagColor(target?.planCode, target?.planExpiresAt)">
            {{ displayPlanName(target?.planName, target?.planCode) }}
          </a-tag>
          <span class="muted small">
            {{ target?.planExpiresAt ? `${formatDateTime(target.planExpiresAt)} · ${untilNow(target.planExpiresAt)}` : '永久' }}
          </span>
        </a-form-item>
        <a-form-item label="新档位">
          <a-select v-model:value="planCode" style="width: 100%">
            <a-select-option v-for="p in planOptions" :key="p.code" :value="p.code">
              {{ p.label }}<span class="muted"> · {{ p.code }}</span>
            </a-select-option>
          </a-select>
        </a-form-item>

        <template v-if="planCode !== 'free'">
          <a-form-item label="有效期">
            <a-radio-group v-model:value="planTerm" class="plan-term">
              <a-radio v-for="t in planTermOptions" :key="String(t.value)" :value="t.value">
                {{ t.label }}
              </a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item v-if="planTerm === 'custom'" label="指定到期时间">
            <a-date-picker
              v-model:value="planCustomDate"
              show-time
              style="width: 100%"
              placeholder="选择到期日期与时间"
            />
          </a-form-item>
          <a-alert
            type="info"
            show-icon
            class="mb16"
            :message="`调整后到期：${planTermPreview}`"
            :description="`POST /api/admin/users/{id}/plan · 写操作会记录审计日志（操作人、目标用户、前后档位）。失败时原样展示后端 message。`"
          />
        </template>
        <a-alert
          v-else
          type="warning"
          show-icon
          message="免费版没有到期时间（永久有效）；切换到免费版会终止当前订阅。"
        />
        <a-alert v-if="planError" type="error" show-icon class="mt8" :message="planError" />
      </a-form>
    </a-modal>

    <!-- 加量 / 开白 -->
    <a-modal
      v-model:open="grantOpen"
      title="加量 / 开白"
      :confirm-loading="grantSaving"
      ok-text="写入权益"
      cancel-text="取消"
      @ok="saveGrant"
    >
      <a-form layout="vertical">
        <a-form-item label="目标用户">
          <span class="mono">{{ target?.username }}（{{ target?.id }}）</span>
        </a-form-item>
        <a-form-item label="权益项">
          <a-select v-model:value="grantKey" style="width: 100%">
            <a-select-option v-for="g in GRANT_KEYS" :key="g.key" :value="g.key">
              {{ g.label }} <span class="muted">· {{ g.key }}</span>
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item :label="`数值（${grantKeyHint}）`" :validate-status="grantError ? 'error' : undefined" :help="grantError">
          <a-input-number v-model:value="grantValue" style="width: 100%" :min="0" :step="1" />
        </a-form-item>
        <a-form-item label="过期时间（可选，ISO 8601 + 时区）">
          <a-input v-model:value="grantExpiresAt" placeholder="例如 2026-12-31T23:59:59+08:00，留空=永久" allow-clear />
        </a-form-item>
        <a-form-item label="备注（可选）">
          <a-input v-model:value="grantNote" placeholder="例如：活动补偿 100GB" allow-clear />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 删除用户（输入用户名二次确认） -->
    <a-modal
      v-model:open="deleteOpen"
      title="删除用户"
      ok-text="永久删除"
      :ok-button-props="{ danger: true, disabled: !deleteConfirmOk }"
      :confirm-loading="deleteSaving"
      cancel-text="取消"
      @ok="doDelete"
    >
      <a-alert
        type="error"
        show-icon
        class="mb16"
        message="删除不可恢复"
        description="将级联删除该用户的设备、工作区关系、传输记录与存储对象。请输入用户名确认。"
      />
      <a-form layout="vertical">
        <a-form-item :label="`请输入用户名「${target?.username}」以确认`" :validate-status="deleteError ? 'error' : undefined" :help="deleteError">
          <a-input v-model:value="deleteInput" :placeholder="target?.username" allow-clear />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  CheckCircleOutlined, CrownOutlined, DeleteOutlined, DownOutlined, GiftOutlined,
  ProfileOutlined, QuestionCircleOutlined,
  ReloadOutlined, SearchOutlined, StopOutlined, TeamOutlined
} from '@ant-design/icons-vue'
import { useRouter } from 'vue-router'
import { adminApi } from '../api/admin'
import sharesApi from '../api/shares'
import { GRANT_KEYS, userStatusLabel } from '../constants/plans'
import { useAuthStore } from '../stores/auth'
import {
  ENDPOINT_MISSING_TEXT,
  errorStatus,
  errorText,
  isEndpointMissing
} from '../utils/errors'
import { formatBytes, formatDateTime, formatNumber, fromNow, maskEmail, untilNow } from '../utils/format'
import {
 AUDIT_SCAN_SIZE,
  WORKSPACE_SCAN_MAX_PAGES,
  WORKSPACE_SCAN_SIZE,
  auditScopeText,
  filterAuditsForUser,
  pickOwnerWorkspaces,
  shareStateText,
  spaceScopeText,
  sumUsage,
  usagePercent,
  workspaceLabel
} from '../utils/userDetail'
import {
  collectPlanOptions,
  displayPlanName,
  isPlanExpired,
  monthsLaterText,
  planConfirmText,
  planExpiryText,
  planTagColor,
  PLAN_TERM_OPTIONS,
  toOffsetIso,
  type PlanTerm
} from '../utils/plan'
import type { AdminShareItem, AdminUser, AuditLog, GrantRequest, SetPlanRequest, WorkspaceItem } from '../types'
import dayjs, { type Dayjs } from 'dayjs'

const auth = useAuthStore()

const loading = ref(false)
const loadError = ref('')
const items = ref<AdminUser[]>([])
const stats = ref({ total: 0, active: 0, disabled: 0, admin: 0 })
const page = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const busyId = ref<string | null>(null)

/** 邮箱接口是否上线：以后端响应里是否出现 email 字段为准（未上线时字段缺省） */
const emailSupported = ref<boolean | null>(null)

const columns = [
  { title: '用户', key: 'user', width: 240 },
  { title: '邮箱', key: 'email', width: 190 },
  { title: '状态', key: 'status', width: 84 },
  { title: '角色', key: 'role', width: 84 },
  { title: '档位', key: 'plan', width: 190 },
  // 1440 宽下优先收起「设备/传输」「注册时间」，保证操作列不被横向滚动挤压
  { title: '设备 / 传输', key: 'counts', width: 150, responsive: ['xxl'] as const },
  { title: '注册时间', key: 'createdAt', width: 160, responsive: ['xxl'] as const },
  { title: '操作', key: 'actions', width: 300, fixed: 'right' as const }
]

const paginationTotal = computed(() => {
  const base = (page.value - 1) * pageSize.value + items.value.length
  return items.value.length === pageSize.value ? base + 1 : base
})

// ── 详情 ──
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<AdminUser | null>(null)

// 抽屉内三个数据区块各自独立加载/错误（task-122）
const detailSpaces = ref<WorkspaceItem[]>([])
const spacesLoading = ref(false)
const spacesError = ref('')
const spacesPages = ref(0)
const detailShares = ref<AdminShareItem[]>([])
const detailSharesTotal = ref(0)
const sharesLoading = ref(false)
const sharesError = ref('')
const detailAudits = ref<AuditLog[]>([])
const auditScanned = ref(0)
const auditsLoading = ref(false)
const auditsError = ref('')

const router = useRouter()

// ── 邮箱 ──
const emailOpen = ref(false)
const emailSaving = ref(false)
const emailInput = ref('')
const emailError = ref('')

// ── 档位 ──
const planOpen = ref(false)
const planSaving = ref(false)
const planCode = ref('plus')
const planTerm = ref<PlanTerm>('keep')
const planCustomDate = ref<Dayjs | undefined>(undefined)
const planError = ref('')

// ── 加量 ──
const grantOpen = ref(false)
const grantSaving = ref(false)
const grantKey = ref('storage_bytes')
const grantValue = ref<number | null>(null)
const grantExpiresAt = ref('')
const grantNote = ref('')
const grantError = ref('')

// ── 删除 ──
const deleteOpen = ref(false)
const deleteSaving = ref(false)
const deleteInput = ref('')
const deleteError = ref('')

const target = ref<AdminUser | null>(null)

const planOptions = computed(() => {
  const list: AdminUser[] = [...items.value]
  if (target.value) list.push(target.value)
  return collectPlanOptions(list)
})

const planTermOptions = computed(() =>
  PLAN_TERM_OPTIONS.filter((o) => o.value !== 'keep' || !!target.value?.planExpiresAt)
)

const planTermPreview = computed(() => {
  if (!target.value) return '—'
  if (planCode.value === 'free') return '永久（免费版无到期时间）'
  const term = planTerm.value
  if (term === 'keep') return planExpiryText(target.value.planExpiresAt)
  if (term === 'custom') {
    return planCustomDate.value ? formatDateTime(planCustomDate.value.valueOf()) : '请选择到期时间'
  }
  return monthsLaterText(term)
})

watch(planCode, () => {
  planError.value = ''
  if (planCode.value !== 'free' && planTerm.value === 'keep' && !target.value?.planExpiresAt) {
    planTerm.value = 1
  }
})

const grantKeyHint = computed(() => GRANT_KEYS.find((g) => g.key === grantKey.value)?.hint || '')
const deleteConfirmOk = computed(
  () => !!target.value && deleteInput.value.trim() === (target.value.username || '')
)

watch(deleteInput, () => {
  deleteError.value = ''
})
watch(emailInput, () => {
  emailError.value = ''
})

function isSelf(user: AdminUser): boolean {
  return !!auth.user?.id && String(auth.user.id) === String(user.id)
}

function canReset(_user: AdminUser): boolean {
  return emailSupported.value === true
}

function canEditEmail(_user: AdminUser): boolean {
  return emailSupported.value === true
}

function resetTooltip(user: AdminUser): string {
  if (emailSupported.value === null) return '邮箱接口状态待确认：点「刷新」后重试'
  if (emailSupported.value !== true) return '列表接口未返回 email 字段：无法确定邮箱，暂不可重置'
  if (!user.email) return '该用户未绑定邮箱：点击后由后端返回 400 并说明原因（原样展示）'
  return '生成临时密码并发送至用户邮箱'
}

function emailTooltip(user: AdminUser): string {
  if (emailSupported.value === null) return '邮箱接口状态待确认：点「刷新」后重试'
  if (emailSupported.value !== true) return '列表接口未返回 email 字段：暂不可修改邮箱'
  return user.email ? '修改该用户登录邮箱' : '为该用户绑定邮箱'
}

async function loadStats() {
  try {
    const res = await adminApi.userStats()
    if (res.code === 200 && res.data) stats.value = res.data
  } catch {
    // 统计失败不影响列表
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const res = await adminApi.listUsers({ page: page.value, size: pageSize.value, keyword: keyword.value.trim() || undefined })
    if (res.code === 200) {
      items.value = res.data || []
      detectEmailSupport()
    } else {
      loadError.value = res.message || '用户列表加载失败'
      message.error(res.message || '加载失败')
    }
  } catch (err) {
    loadError.value = errorText(err, '用户列表加载失败')
    message.error(errorText(err, '用户列表加载失败'))
  } finally {
    loading.value = false
  }
}

function detectEmailSupport() {
  const first = items.value[0]
  if (!first) return
  emailSupported.value = Object.prototype.hasOwnProperty.call(first, 'email')
}

async function loadAll() {
  await Promise.all([load(), loadStats()])
}

function onSearch() {
  page.value = 1
  load()
}

function onPageChange(p: number) {
  page.value = p
  load()
}

function onSizeChange(_p: number, size: number) {
  pageSize.value = size
  page.value = 1
  load()
}

async function copyId(id: string) {
  try {
    await navigator.clipboard.writeText(String(id))
    message.success('用户 ID 已复制')
  } catch {
    message.warning('复制失败，请手动选择文本')
  }
}

// ── 详情抽屉：五区块（task-122） ──
const usage = computed(() => sumUsage(detailSpaces.value))
const usagePercentValue = computed(() => {
  const p = usagePercent(usage.value.usedBytes, usage.value.maxBytes)
  return p === null ? null : Math.round(p * 10) / 10
})
const usageHeadText = computed(() => {
  const u = usage.value
  if (u.withQuota === 0) return `已用 ${formatBytes(u.usedBytes)}（后端未返回配额）`
  return `已用 ${formatBytes(u.usedBytes)} / ${formatBytes(u.maxBytes)}（${usagePercentValue.value}%）`
})
const spacesScopeHint = computed(() => spaceScopeText(spacesPages.value || 1, detailSpaces.value.length))
const auditScopeNote = computed(() => auditScopeText(auditScanned.value, detailAudits.value.length))

function spacePercent(w: WorkspaceItem): number {
  const p = usagePercent(Number(w.usedSize), Number(w.maxSize))
  return p === null ? 0 : Math.round(p * 10) / 10
}

function shareTagColor(s: AdminShareItem): string {
  if (s.status === 1) return 'default'
  if (s.expiresAt != null && s.expiresAt <= Date.now()) return 'orange'
  return 'green'
}

function auditTargetTypeLabel(type: string): string {
  const labels: Record<string, string> = {
    user: '用户',
    workspace: '工作区',
    transfer: '传输任务',
    announcement: '公告',
    share: '分享链接',
    software: '软件分发',
    plan: '档位',
    config: '系统配置',
    email: '邮件',
    entitlement: '权益'
  }
  return labels[type] || type
}

function goShares() {
  if (!detail.value) return
  detailOpen.value = false
  router.push({ path: '/shares', query: { owner: detail.value.username } })
}

async function openDetail(user: AdminUser) {
  detail.value = user
  detailOpen.value = true
  detailLoading.value = true
  detailSpaces.value = []
  spacesError.value = ''
  spacesPages.value = 0
  detailShares.value = []
  detailSharesTotal.value = 0
  sharesError.value = ''
  detailAudits.value = []
  auditScanned.value = 0
  auditsError.value = ''
  // 三个区块并行、各自独立；任何一块失败不影响其它区块和基础信息
  void loadDetailSpaces()
  void loadDetailShares()
  void loadDetailAudits()
  try {
    const res = await adminApi.getUserDetail(user.id)
    if (res.code === 200 && res.data) {
      detail.value = res.data
      if (Object.prototype.hasOwnProperty.call(res.data, 'email')) {
        emailSupported.value = true
      }
      syncRow(res.data)
    } else {
      message.error(res.message || '详情加载失败')
    }
  } catch (err) {
    message.error(errorText(err, '详情加载失败'))
  } finally {
    detailLoading.value = false
  }
}

function syncRow(updated: AdminUser) {
  const idx = items.value.findIndex((u) => String(u.id) === String(updated.id))
  if (idx >= 0) {
    items.value[idx] = { ...items.value[idx], ...updated }
  }
}

/** 操作成功后就地刷新列表行 + 打开中的抽屉，不整页刷新 */
async function refreshAfterAction(userId: string) {
  await load()
  if (detailOpen.value && detail.value && String(detail.value.id) === String(userId)) {
    try {
      const res = await adminApi.getUserDetail(userId)
      if (res.code === 200 && res.data) {
        detail.value = res.data
        syncRow(res.data)
      }
    } catch {
      // 抽屉内刷新失败不打断成功提示；用户可重新打开抽屉
    }
  }
}

async function loadDetailSpaces() {
  const user = detail.value
  if (!user) return
  spacesLoading.value = true
  spacesError.value = ''
  const picked: WorkspaceItem[] = []
  let pages = 0
  try {
    // 后端 /admin/workspaces 只有分页参数，没有 ownerKeyword：按页扫描后前端按 ownerId 过滤
    for (let p = 1; p <= WORKSPACE_SCAN_MAX_PAGES; p++) {
      const res = await adminApi.listWorkspaces({ page: p, size: WORKSPACE_SCAN_SIZE })
      if (res.code !== 200) {
        spacesError.value = res.message || `工作区接口返回 code=${res.code}`
        return
      }
      const chunk = res.data || []
      pages = p
      picked.push(...pickOwnerWorkspaces(chunk, user.id))
      if (chunk.length < WORKSPACE_SCAN_SIZE) break
    }
    detailSpaces.value = picked
    spacesPages.value = pages
  } catch (e) {
    spacesError.value = errorText(e, '空间加载失败')
  } finally {
    spacesLoading.value = false
  }
}

async function loadDetailShares() {
  const user = detail.value
  if (!user) return
  sharesLoading.value = true
  sharesError.value = ''
  try {
    const res = await sharesApi.list({ page: 1, size: 5, ownerKeyword: user.username })
    if (res.code !== 200) {
      sharesError.value = res.message || `分享接口返回 code=${res.code}`
      return
    }
    detailShares.value = res.data?.items || []
    detailSharesTotal.value = res.data?.total || 0
  } catch (e) {
    sharesError.value = errorText(e, '分享加载失败')
  } finally {
    sharesLoading.value = false
  }
}

async function loadDetailAudits() {
  const user = detail.value
  if (!user) return
  auditsLoading.value = true
  auditsError.value = ''
  try {
    // 后端只支持 actorId（操作人）过滤，没有 targetId：取最近一批后前端按「目标/操作人」过滤
    const res = await adminApi.listAuditLogs({ page: 1, size: AUDIT_SCAN_SIZE })
    if (res.code !== 200) {
      auditsError.value = res.message || `审计接口返回 code=${res.code}`
      return
    }
    const logs = res.data?.items || []
    auditScanned.value = logs.length
    detailAudits.value = filterAuditsForUser(logs, user.id)
  } catch (e) {
    auditsError.value = errorText(e, '审计加载失败')
  } finally {
    auditsLoading.value = false
  }
}

// ── 状态 / 管理员 ──
function toggleStatus(user: AdminUser) {
  const disabling = user.status === 1
  Modal.confirm({
    title: disabling ? '禁用账号' : '启用账号',
    content: disabling
      ? `禁用「${user.username}」后，该用户所有设备将无法继续使用服务（登录与传输被拒绝）。`
      : `启用「${user.username}」后，该账号可正常登录与传输。`,
    okText: disabling ? '确认禁用' : '确认启用',
    okType: disabling ? 'danger' : 'primary',
    cancelText: '取消',
    onOk: async () => {
      busyId.value = user.id
      try {
        const res = await adminApi.setUserStatus(user.id, disabling ? 0 : 1)
        if (res.code === 200) {
          message.success(disabling ? '账号已禁用' : '账号已启用')
          await refreshAfterAction(user.id)
        } else {
          message.error(res.message || '操作失败')
        }
      } catch (err) {
        message.error(errorText(err, '操作失败'))
      } finally {
        busyId.value = null
      }
    }
  })
}

function toggleAdmin(user: AdminUser) {
  const grant = !user.isAdmin
  Modal.confirm({
    title: grant ? '设为管理员' : '取消管理员',
    content: grant
      ? `「${user.username}」将拥有全部后台管理权限（含删除用户、修改配置）。`
      : `取消「${user.username}」的管理员权限，其将无法访问管理后台。`,
    okText: grant ? '确认设置' : '确认取消',
    okType: grant ? 'primary' : 'danger',
    cancelText: '取消',
    onOk: async () => {
      busyId.value = user.id
      try {
        const res = await adminApi.setAdmin(user.id, grant)
        if (res.code === 200) {
          message.success(grant ? '已设为管理员' : '已取消管理员')
          await refreshAfterAction(user.id)
        } else {
          message.error(res.message || '操作失败')
        }
      } catch (err) {
        message.error(errorText(err, '操作失败'))
      } finally {
        busyId.value = null
      }
    }
  })
}

// ── 重置密码（真实接口；返回 400 时原样展示后端原因） ──
function resetPassword(user: AdminUser) {
  if (emailSupported.value !== true) {
    message.warning(resetTooltip(user))
    return
  }
  if (!user.email) {
    // 未绑定邮箱：不做前端拦截，直接请求后端，让真实的 400 原因出现在界面上
    void sendResetPassword(user)
    return
  }
  Modal.confirm({
    title: '重置密码',
    content: `将为「${user.username}」生成一个随机临时密码，并通过邮件发送至 ${maskEmail(
      user.email
    )}。管理员不会看到密码明文，用户需用临时密码登录后自行修改。`,
    okText: '发送重置邮件',
    cancelText: '取消',
    onOk: () => sendResetPassword(user)
  })
}

async function sendResetPassword(user: AdminUser) {
  busyId.value = user.id
  try {
    const res = await adminApi.resetUserPassword(user.id)
    if (res.code === 200) {
      const masked = res.data?.emailMasked || (user.email ? maskEmail(user.email) : '用户邮箱')
      message.success(`临时密码已发送至 ${masked}`)
      await load()
    } else {
      message.error(res.message || '重置失败')
    }
  } catch (err) {
    if (isEndpointMissing(err)) {
      emailSupported.value = false
      message.warning(ENDPOINT_MISSING_TEXT)
    } else {
      // 400（未绑定邮箱）/ 409 / 503（发信服务未配置、发送失败）：原样显示后端 message，绝不吞掉
      message.error(errorText(err, '重置密码失败'))
    }
  } finally {
    busyId.value = null
  }
}

// ── 修改邮箱 ──
function openEmail(user: AdminUser) {
  if (!canEditEmail(user)) {
    message.warning(emailTooltip(user))
    return
  }
  target.value = user
  emailInput.value = user.email || ''
  emailError.value = ''
  emailOpen.value = true
}

function validateEmail(value: string): string {
  const v = value.trim()
  if (!v) return '请输入邮箱'
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(v)) return '邮箱格式不正确'
  return ''
}

async function saveEmail() {
  if (!target.value) return
  const err = validateEmail(emailInput.value)
  if (err) {
    emailError.value = err
    return
  }
  emailSaving.value = true
  try {
    const res = await adminApi.updateUserEmail(target.value.id, emailInput.value.trim())
    if (res.code === 200) {
      message.success('邮箱已更新')
      emailOpen.value = false
      await refreshAfterAction(target.value.id)
    } else {
      message.error(res.message || '保存失败')
    }
  } catch (e) {
    const status = errorStatus(e)
    if (status === 409) {
      // 后端 ConflictException：该邮箱已被其他账号绑定
      emailError.value = errorText(e, '该邮箱已被其他账号占用')
      message.error(emailError.value)
    } else if (isEndpointMissing(e)) {
      emailSupported.value = false
      message.warning(ENDPOINT_MISSING_TEXT)
    } else {
      // 400（格式/未绑定邮箱）/ 503（发信未配置）等：原样显示后端 message
      emailError.value = errorText(e, '保存失败')
      message.error(emailError.value)
    }
  } finally {
    emailSaving.value = false
  }
}

// ── 档位（task-104） ──
function openPlan(user: AdminUser) {
  target.value = user
  planCode.value = user.planCode || 'free'
  planTerm.value = user.planExpiresAt ? 'keep' : 1
  planCustomDate.value = undefined
  planError.value = ''
  planOpen.value = true
}

function savePlan() {
  if (!target.value) return
  if (planCode.value !== 'free' && planTerm.value === 'custom' && !planCustomDate.value) {
    planError.value = '请选择到期时间'
    message.warning(planError.value)
    return
  }
  const fromLabel = displayPlanName(target.value.planName, target.value.planCode)
  const toLabel = planOptions.value.find((p) => p.code === planCode.value)?.label || planCode.value
  Modal.confirm({
    title: '确认调整档位',
    content: planConfirmText(target.value.username, fromLabel, toLabel, planTermPreview.value),
    okText: '确认调整',
    cancelText: '取消',
    onOk: () => doSavePlan()
  })
}

async function doSavePlan() {
  const user = target.value
  if (!user) return
  const body: SetPlanRequest = { planCode: planCode.value }
  if (planCode.value !== 'free') {
    if (planTerm.value === 'keep' && user.planExpiresAt) {
      body.expiresAt = toOffsetIso(new Date(user.planExpiresAt))
    } else if (planTerm.value === 'custom' && planCustomDate.value) {
      body.expiresAt = toOffsetIso(planCustomDate.value.toDate())
    } else if (typeof planTerm.value === 'number') {
      body.months = planTerm.value
    }
  }
  planSaving.value = true
  try {
    const res = await adminApi.setUserPlan(user.id, body)
    if (res.code === 200) {
      const toLabel = planOptions.value.find((p) => p.code === planCode.value)?.label || planCode.value
      message.success(`已将「${user.username}」的档位调整为「${toLabel}」`)
      planOpen.value = false
      // 档位属于后端聚合字段：成功必须重拉列表，不做本地臆造
      await refreshAfterAction(user.id)
    } else {
      planError.value = res.message || '调整档位失败'
      message.error(planError.value)
    }
  } catch (err) {
    planError.value = isEndpointMissing(err) ? ENDPOINT_MISSING_TEXT : errorText(err, '调整档位失败')
    message.error(planError.value)
  } finally {
    planSaving.value = false
  }
}

// ── 加量 / 开白 ──
function openGrant(user: AdminUser) {
  target.value = user
  grantKey.value = 'storage_bytes'
  grantValue.value = null
  grantExpiresAt.value = ''
  grantNote.value = ''
  grantError.value = ''
  grantOpen.value = true
}

/** 允许 YYYY-MM-DD 或完整 OffsetDateTime；纯日期补 00:00:00+08:00，避免后端 OffsetDateTime 解析失败 */
function normalizeDateTime(input: string): string | null {
  const v = input.trim()
  if (!v) return null
  if (/^\d{4}-\d{2}-\d{2}$/.test(v)) return `${v}T00:00:00+08:00`
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(v)) return `${v}+08:00`
  return v
}

async function saveGrant() {
  if (!target.value) return
  if (grantValue.value === null || Number.isNaN(Number(grantValue.value))) {
    grantError.value = '请输入数值'
    return
  }
  const body: GrantRequest = {
    userId: target.value.id,
    key: grantKey.value,
    value: Number(grantValue.value),
    expiresAt: normalizeDateTime(grantExpiresAt.value)
  }
  if (grantNote.value.trim()) body.note = grantNote.value.trim()
  grantSaving.value = true
  try {
    const res = await adminApi.grantEntitlement(body)
    if (res.code === 200) {
      message.success('权益已写入（后端会立即刷新配额）')
      grantOpen.value = false
    } else {
      message.error(res.message || '写入失败')
    }
  } catch (err) {
    message.error(isEndpointMissing(err) ? ENDPOINT_MISSING_TEXT : errorText(err, '写入权益失败'))
  } finally {
    grantSaving.value = false
  }
}

// ── 删除 ──
function openDelete(user: AdminUser) {
  target.value = user
  deleteInput.value = ''
  deleteError.value = ''
  deleteOpen.value = true
}

async function doDelete() {
  if (!target.value || !deleteConfirmOk.value) return
  deleteSaving.value = true
  try {
    const res = await adminApi.deleteUser(target.value.id)
    if (res.code === 200) {
      message.success(`用户「${target.value.username}」已删除`)
      deleteOpen.value = false
      await loadAll()
    } else {
      deleteError.value = res.message || '删除失败'
    }
  } catch (err) {
    deleteError.value = errorText(err, '删除失败')
  } finally {
    deleteSaving.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.mb16 {
  margin-bottom: 16px;
}
.mt8 {
  margin-top: 8px;
}
.mt16 {
  margin-top: 16px;
}
.small {
  font-size: 12px;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.stat-chips {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
@media (max-width: 900px) {
  .stat-chips {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.stat-chip {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px;
  border-radius: 12px;
  border: 1px solid var(--app-border);
  background: var(--app-card-bg);
}
.chip-label {
  font-size: 12px;
  color: var(--app-text-secondary);
}
.chip-value {
  font-size: 22px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}
.table-card {
  padding: 8px 8px 12px;
}
.table-card :deep(.ant-table-thead > tr > th) {
  background: transparent;
}
.table-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  flex-wrap: wrap;
}
.search-group {
  display: flex;
  align-items: center;
  gap: 8px;
}
.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}
.user-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.id-sub {
  cursor: copy;
  font-size: 11px;
  color: var(--app-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.id-sub:hover {
  color: var(--brand);
}
.row-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-wrap: wrap;
}
.action-wrap {
  display: inline-flex;
}
.row-actions :deep(.ant-btn-link) {
  padding: 0 6px;
}
.menu-text {
  margin-left: 8px;
}
.table-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 12px 4px;
  flex-wrap: wrap;
}
/* ── 详情抽屉（task-122） ── */
.dd-section {
  margin-bottom: 22px;
}
.dd-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 10px;
}
.dd-title-link {
  margin-left: auto;
  padding: 0;
}
.dd-hint-icon {
  color: var(--app-text-secondary);
  cursor: help;
  font-size: 13px;
}
.dd-summary {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.dd-usage-bar {
  margin-bottom: 10px;
}
.dd-space-row {
  margin-bottom: 10px;
}
.dd-space-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.dd-ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dd-list-row {
  padding: 8px 0;
  border-bottom: 1px dashed var(--app-border);
}
.dd-list-row:last-child {
  border-bottom: 0;
}
.dd-list-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.dd-note {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-secondary);
}
.dd-block-error {
  margin-top: 4px;
}
.dd-block-error :deep(.ant-alert-message) {
  word-break: break-word;
}
</style>
