'use strict';
const page = document.body.dataset.page;
const root = document.querySelector('#app');
const brand = '<div class="brand"><span class="logo">栖</span><span>云栖酒店管理</span></div>';
const escapeHtml = value => String(value ?? '—').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
async function api(path, options = {}, authenticated = true) {
  const headers = {'Content-Type':'application/json'};
  if (authenticated) {
    const token = sessionStorage.getItem('accessToken');
    if (!token) { location.replace('/login.html'); throw new Error('请先登录'); }
    headers.Authorization = `Bearer ${token}`;
  }
  let response;
  try { response = await fetch(path, {...options, headers, credentials:'omit'}); }
  catch { throw new Error('无法连接服务器，请检查项目是否已启动'); }
  const text = await response.text();
  let data; try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  if (!response.ok) {
    if (response.status === 401 && authenticated) {
      sessionStorage.clear(); location.replace('/login.html'); throw new Error('登录已过期，请重新登录');
    }
    const messages = {400:'提交的信息不完整或格式不正确',401:'用户名或密码错误',403:'账号已禁用或没有访问权限',404:'查询的酒店、房型或订单不存在',409:path.includes('/register')?'用户名已存在，请更换用户名':'当前状态不允许此操作，请刷新后重试'};
    throw new Error(data?.message || messages[response.status] || `请求失败（${response.status}），请稍后重试`);
  }
  return data;
}
function authPage() {
  const register = page === 'register';
  root.innerHTML = `<div class="auth"><section class="story">${brand}<div><div class="eyebrow" style="color:#afc8b9">A BETTER WAY TO HOST</div><h1>从容管理，<br>让每一次入住更美好。</h1><p>连接酒店与服务，在一个工作台中掌握酒店资料与房型信息。</p></div><footer>云栖 · 酒店信息管理平台</footer></section><main class="auth-side"><div class="auth-card"><div class="eyebrow">WELCOME TO YUNQI</div><h2>${register?'创建你的账号':'欢迎回来'}</h2><p class="muted">${register?'注册账号，开启酒店管理之旅。':'登录账号，继续你的酒店管理工作。'}</p><form id="auth-form"><label for="username">用户名</label><input id="username" name="username" autocomplete="username" required maxlength="50" placeholder="请输入用户名"><label for="password">密码</label><input id="password" name="password" type="password" autocomplete="${register?'new-password':'current-password'}" required ${register?'minlength="8"':''} placeholder="${register?'至少 8 个字符':'请输入密码'}">${register?'<label for="confirm">确认密码</label><input id="confirm" type="password" autocomplete="new-password" required placeholder="请再次输入密码">':''}<button type="submit">${register?'注册账号':'登录工作台'} →</button><div id="message" class="notice" role="status" aria-live="polite"></div></form><p class="switch">${register?'已有账号？ <a href="/login.html">返回登录</a>':'还没有账号？ <a href="/register.html">立即注册</a>'}</p></div></main></div>`;
  document.querySelector('#auth-form').addEventListener('submit', async event => {
    event.preventDefault();
    const username = document.querySelector('#username').value.trim();
    const password = document.querySelector('#password').value;
    const message = document.querySelector('#message');
    const button = event.target.querySelector('button');
    message.textContent = ''; message.className = 'notice';
    if (!username) { message.textContent = '用户名不能为空'; return; }
    if (register && password !== document.querySelector('#confirm').value) { message.textContent = '两次输入的密码不一致'; return; }
    if (register && new TextEncoder().encode(password).length > 72) { message.textContent = '密码不能超过 72 字节'; return; }
    button.disabled = true;
    try {
      const data = await api(`/hotel/auth/${register?'register':'login'}`, {method:'POST',body:JSON.stringify(register?{username,passwordHash:password}:{username,password})}, false);
      if (register) {
        event.target.reset();
        const card = document.querySelector('.auth-card');
        card.innerHTML = `<section class="registration-success" aria-labelledby="success-title"><div class="success-icon" aria-hidden="true">✓</div><div class="eyebrow">ACCOUNT CREATED</div><h2 id="success-title" tabindex="-1">注册成功！</h2><p class="muted">账号 <strong>${escapeHtml(username)}</strong> 已创建。<br>现在可以使用你的用户名和密码登录。</p><a class="success-action" href="/login.html">前往登录 →</a><p class="muted">注册不会自动登录，请在登录页面完成验证。</p></section>`;
        document.querySelector('#success-title').focus();
      } else {
        if (!data?.accessToken) throw new Error('登录响应缺少令牌，请检查后端配置');
        sessionStorage.removeItem('pendingOrderId');
        sessionStorage.setItem('accessToken',data.accessToken);
        sessionStorage.setItem('username',data.username || username);
        location.replace('/index.html');
      }
    } catch (error) { message.textContent = error.message; }
    finally { button.disabled = false; }
  });
}
async function dashboard() {
  if (!sessionStorage.getItem('accessToken')) { location.replace('/login.html'); return; }
  root.innerHTML = `<div class="layout"><aside class="sidebar">${brand}<div><div class="eyebrow">WORKSPACE</div><nav><button class="nav-item active" data-view="hotels">▦　酒店管理</button><button class="nav-item" data-view="business">◇　办理酒店业务</button><button class="nav-item" data-view="orders">▣　我的订单</button><button class="nav-item" data-view="stays">✓　待入住</button><button class="nav-item" data-view="refunds">↩　退款</button><button class="nav-item" data-view="profile">◎　个人中心</button><button class="nav-item" data-view="vip">♛　VIP会员</button><button class="nav-item" data-view="pet">♧　我的灵宠</button></nav></div><div class="sidebar-bottom"><p class="muted">专注服务，从容管理</p><button class="secondary" id="logout">退出登录</button></div></aside><main class="main"><header class="topbar"><span>工作台 / <span id="breadcrumb">酒店管理</span></span><div><span id="account"></span> <button class="secondary" id="logout-top">退出</button></div></header><div class="page-heading"><div><div class="eyebrow">PROPERTY OVERVIEW</div><h1 id="view-title">酒店管理</h1><div class="muted" id="view-subtitle">查看酒店资料，快速找到你需要的信息。</div></div><span class="badge">酒店信息管理平台</span></div><section class="stats"><article class="stat"><span>酒店总数</span><strong id="total">—</strong><span>当前可查询酒店</span></article><article class="stat"><span>本次查询</span><strong id="matches">—</strong><span>条酒店记录</span></article><article class="stat"><span>账号状态</span><strong style="font-size:23px">已登录</strong><span>欢迎使用酒店工作台</span></article></section><section id="content" class="panel"></section><div id="page-message" class="notice" role="status" aria-live="polite"></div></main></div><dialog id="detail"><h2>酒店详情</h2><div id="detail-body"></div><button id="close-detail" class="secondary">关闭</button></dialog>`;
  document.querySelector('#account').textContent = sessionStorage.getItem('username') || '当前用户';
  const logout = () => { sessionStorage.removeItem('accessToken'); sessionStorage.removeItem('username'); sessionStorage.removeItem('pendingOrderId'); location.replace('/login.html'); };
  document.querySelector('#logout').onclick = logout; document.querySelector('#logout-top').onclick = logout;
  document.querySelector('#close-detail').onclick = () => document.querySelector('#detail').close();
  let hotelQuery = ''; let hotelPage = 0; let hotelSearchRequest = 0;
  const content = document.querySelector('#content');
  const message = document.querySelector('#page-message');
  let viewVersion = 0;
  let stayExpiryTimer;
  let vipRefreshTimer;
  let orderFilter = '';
  let orderKeyword = '';
  const statusNames = {PENDING_PAYMENT:'待付款',AWAITING_CHECK_IN:'待入住',COMPLETED:'已结束',REFUND_REQUESTED:'待退款',REFUNDED:'已退款',CANCELLED:'已取消'};
  const displayTime = value => value ? String(value).replace('T', ' ').slice(0, 19) : '待生成';
  const stayEndMillis = value => new Date(String(value) + '+08:00').getTime();
  function heading(title, subtitle) {
    document.querySelector('#view-title').textContent = title;
    document.querySelector('#breadcrumb').textContent = title;
    document.querySelector('#view-subtitle').textContent = subtitle;
  }
  const money = value => `¥${Number(value).toFixed(2)}`;
  const orderDetails = order => [['订单编号',order.id],['酒店名称',order.hotelName],['酒店地址',order.hotelAddress],['联系电话',order.hotelPhone],['房型',order.roomName],['可住人数',order.capacity == null?'待确认':`${order.capacity} 人`],['房型介绍',order.description],['购买规格','1 间 · 1 晚'],['入住时间','付款成功 1 小时后（北京时间）'],['退房时间','入住 24 小时后'],['房间号','付款后自动分配三位数字']].map(([key,value]) => `<div class="detail-row"><span class="muted">${key}</span><span>${escapeHtml(value)}</span></div>`).join('');
  async function paymentView(orderId) {
    const version = ++viewVersion;
    clearTimeout(stayExpiryTimer);
    clearInterval(vipRefreshTimer);
    document.querySelectorAll('[data-view]').forEach(b => b.classList.toggle('active', b.dataset.view === 'business'));
    heading('确认付款','核对酒店和房型信息，完成付款后进入待入住。');
    content.innerHTML = '<div class="empty">正在加载订单…</div>';
    try {
      const order = await api(`/hotel/orders/${encodeURIComponent(orderId)}`);
      if (version !== viewVersion) return;
      if (order.status === 'AWAITING_CHECK_IN') { sessionStorage.removeItem('pendingOrderId'); navigate('stays'); return; }
      if (order.status !== 'PENDING_PAYMENT') {
        sessionStorage.removeItem('pendingOrderId'); navigate('orders');
        message.textContent = '该订单已结束、取消或进入退款流程，不能继续付款。'; return;
      }
      content.innerHTML = `<div class="payment-layout"><section><h2>确认你的入住选择</h2>${orderDetails(order)}</section><aside class="payment-summary"><span class="eyebrow">ORDER SUMMARY</span><h2>付款明细</h2><p class="muted">房型费用 · 1 间 × 1 晚</p><div class="payment-total">${escapeHtml(money(order.amount))}</div><div class="simulation-note"><strong>模拟付款</strong><p>点击确认即可完成演示流程，不会扣款。</p></div><button id="confirm-pay">确认付款 ${escapeHtml(money(order.amount))}</button><button id="back-business" class="secondary">返回酒店业务</button><p class="muted">付款后可在「待入住」中查看订单。</p><div id="payment-message" class="notice" role="status"></div></aside></div>`;
      document.querySelector('#back-business').onclick = () => navigate('business');
      document.querySelector('#confirm-pay').onclick = async event => {
        const button = event.currentTarget; button.disabled = true; button.textContent = '正在确认付款…';
        const notice = document.querySelector('#payment-message'); notice.textContent = '';
        try {
          await api(`/hotel/orders/${encodeURIComponent(order.id)}/pay`, {method:'POST'});
          sessionStorage.removeItem('pendingOrderId');
          if (version === viewVersion) { navigate('stays'); message.className = 'notice success'; message.textContent = '模拟付款成功！订单已加入待入住。'; }
        } catch(error) { notice.textContent = error.message; }
        finally { button.disabled = false; button.textContent = `确认付款 ${money(order.amount)}`; }
      };
    } catch(error) {
      if (version !== viewVersion) return;
      content.innerHTML = '<div class="empty">订单加载失败，请重试。</div><button id="retry-payment">重新加载订单</button>';
      message.textContent = error.message;
      document.querySelector('#retry-payment').onclick = () => paymentView(orderId);
    }
  }
  async function staysView() {
    const version = viewVersion;
    content.innerHTML = '<div class="empty">正在加载待入住订单…</div>';
    try {
      const orders = await api('/hotel/orders');
      if (version !== viewVersion) return;
      if (!Array.isArray(orders)) throw new Error('订单列表响应格式不正确');
      content.innerHTML = `<div class="stay-heading"><h2>我的待入住 <span class="badge">${orders.length} 笔</span></h2><button class="secondary" id="refresh-stays">刷新订单</button></div><p class="muted">付款 1 小时后入住，入住时长为 24 小时。退房时间到达后自动移出列表。时间均为北京时间。</p><div id="stay-list">${orders.length ? orders.map(order => `<article class="stay-card"><header><div><span class="eyebrow">ORDER ${escapeHtml(order.id)}</span><h3>${escapeHtml(order.hotelName)} · ${escapeHtml(order.roomName)}</h3></div><span class="badge">待入住</span></header><p class="muted">${escapeHtml(order.hotelAddress)} · ${escapeHtml(order.hotelPhone)}</p><div class="stay-facts"><span>1 间 · 1 晚 · 可住 ${escapeHtml(order.capacity)} 人</span><strong>${escapeHtml(money(order.amount))}</strong></div><div class="stay-schedule"><span>房间号 <strong>${escapeHtml(order.roomNumber)}</strong></span><span>入住：${escapeHtml(displayTime(order.checkInTime))}</span><span>退房：${escapeHtml(displayTime(order.checkOutTime))}</span></div><p class="muted">${escapeHtml(order.description || '暂无房型介绍')}</p><footer>模拟付款完成时间：${escapeHtml(displayTime(order.paidTime))}</footer></article>`).join('') : '<div class="empty">还没有待入住订单。前往办理酒店业务，选择房型并完成付款。</div>'}</div><button class="secondary" id="browse-hotels">继续选择酒店</button>`;
      const ends = orders.map(order => stayEndMillis(order.checkOutTime)).filter(Number.isFinite);
      document.querySelectorAll('#stay-list .stay-card').forEach((card, index) => {
        const actions = document.createElement('div'); actions.className = 'order-actions';
        const button = document.createElement('button'); button.className = 'secondary refund-button'; button.textContent = '退款';
        actions.appendChild(button); card.appendChild(actions);
        button.onclick = async () => {
          button.disabled = true; button.textContent = '正在移入退款…'; message.textContent = '';
          try {
            await api(`/hotel/orders/${encodeURIComponent(orders[index].id)}/refund-request`, {method:'POST'});
            if (version === viewVersion) { navigate('refunds'); message.className = 'notice success'; message.textContent = '订单已移入退款模块，请点击退款并选择原因。'; }
          } catch(error) { if (version === viewVersion) message.textContent = error.message; }
          finally { button.disabled = false; button.textContent = '退款'; }
        };
      });
      if (ends.length) {
        stayExpiryTimer = setTimeout(() => { if (version === viewVersion) navigate('stays'); }, Math.min(2147483647, Math.max(1000, Math.min(...ends) - Date.now() + 100)));
      }
      document.querySelector('#refresh-stays').onclick = () => navigate('stays');
      document.querySelector('#browse-hotels').onclick = () => navigate('business');
    } catch(error) {
      if (version !== viewVersion) return;
      content.innerHTML = '<div class="empty">待入住订单加载失败。</div><button id="retry-stays">重试</button>';
      message.textContent = error.message;
      document.querySelector('#retry-stays').onclick = () => navigate('stays');
    }
  }
  async function refundsView() {
    const version = viewVersion;
    content.innerHTML = '<div class="empty">正在加载退款订单…</div>';
    try {
      const orders = await api('/hotel/orders/refunds');
      if (version !== viewVersion) return;
      if (!Array.isArray(orders)) throw new Error('退款列表响应格式不正确');
      content.innerHTML = `<div class="stay-heading"><h2>我的退款 <span class="badge">${orders.length} 笔</span></h2><button class="secondary" id="refresh-refunds">刷新列表</button></div><p class="muted">移入本模块后，点击卡片上的退款按钮选择原因并确认。当前为模拟全额退款，不产生实际资金退回。</p><div id="refund-list"></div>`;
      document.querySelector('#refresh-refunds').onclick = () => navigate('refunds');
      const list = document.querySelector('#refund-list');
      if (!orders.length) list.innerHTML = '<div class="empty">暂无退款订单。可在待入住的房间卡片上点击退款。</div>';
      orders.forEach(order => {
        const refunded = order.status === 'REFUNDED';
        const card = document.createElement('article'); card.className = 'stay-card';
        card.innerHTML = `<header><div><span class="eyebrow">ORDER ${escapeHtml(order.id)}</span><h3>${escapeHtml(order.hotelName)} · ${escapeHtml(order.roomName)}</h3></div><span class="badge ${refunded?'':'refund-badge'}">${refunded?'已退款':'待退款'}</span></header><p class="muted">${escapeHtml(order.hotelAddress)} · ${escapeHtml(order.hotelPhone)}</p><div class="stay-facts"><span>1 间 · 1 晚 · 可住 ${escapeHtml(order.capacity)} 人</span><strong>${escapeHtml(money(order.amount))}</strong></div><div class="stay-schedule"><span>房间号 <strong>${escapeHtml(order.roomNumber)}</strong></span><span>原入住：${escapeHtml(displayTime(order.checkInTime))}</span><span>原退房：${escapeHtml(displayTime(order.checkOutTime))}</span></div><p class="muted">${escapeHtml(order.description || '暂无房型介绍')}</p><footer>移入退款时间：${escapeHtml(displayTime(order.refundRequestTime))}</footer>${refunded?`<div class="refund-result"><strong>模拟全额退款已完成</strong><p>退款原因：${escapeHtml(order.refundReason)}</p><p>退款时间：${escapeHtml(displayTime(order.refundTime))}</p></div>`:'<div class="order-actions"><button class="refund-button">退款</button></div>'}`;
        if (!refunded) card.querySelector('button').onclick = () => openRefundDialog(order);
        if (refunded) {
          const actions = document.createElement('div'); actions.className = 'order-actions';
          const remove = document.createElement('button'); remove.className = 'secondary'; remove.textContent = '删除';
          remove.setAttribute('aria-label', `删除 ${order.hotelName} ${order.roomName} 的已退款记录`);
          actions.appendChild(remove); card.appendChild(actions);
          remove.onclick = async () => {
            remove.disabled = true; remove.textContent = '正在删除…';
            try {
              await api(`/hotel/orders/${encodeURIComponent(order.id)}/refund`, {method:'DELETE'});
              if (version === viewVersion) { navigate('refunds'); message.className = 'notice success'; message.textContent = '已删除退款卡片。'; }
            } catch(error) { if (version === viewVersion) message.textContent = error.message; }
            finally { remove.disabled = false; remove.textContent = '删除'; }
          };
        }
        list.appendChild(card);
      });
    } catch(error) {
      if (version !== viewVersion) return;
      content.innerHTML = '<div class="empty">退款列表加载失败。</div><button id="retry-refunds">重试</button>';
      message.textContent = error.message;
      document.querySelector('#retry-refunds').onclick = () => navigate('refunds');
    }
  }
  async function ordersView() {
    const version = viewVersion;
    content.innerHTML = '<div class="empty">正在加载全部订单…</div>';
    try {
      const orders = await api('/hotel/orders/all');
      if (version !== viewVersion) return;
      if (!Array.isArray(orders)) throw new Error('订单列表响应格式不正确');
      content.innerHTML = `<div class="stay-heading"><h2>我的订单 <span class="badge">${orders.length} 笔</span></h2><button id="refresh-orders" class="secondary">刷新订单</button></div><p class="muted">查看全部购买记录，包括已结束的入住订单和从退款列表删除的历史订单。</p><form class="filters" id="order-filters"><select id="order-status" aria-label="订单状态"><option value="">全部状态</option>${Object.entries(statusNames).map(([key,label]) => `<option value="${key}">${label}</option>`).join('')}</select><input id="order-keyword" aria-label="搜索订单" placeholder="搜索酒店、房型或订单编号"><button>筛选</button></form><div id="all-order-list" aria-live="polite"></div>`;
      const select = document.querySelector('#order-status'); select.value = orderFilter;
      const keyword = document.querySelector('#order-keyword'); keyword.value = orderKeyword;
      const render = () => {
        orderFilter = select.value; orderKeyword = keyword.value;
        const query = keyword.value.trim().toLowerCase();
        const matches = orders.filter(order => (!select.value || order.status === select.value) && [order.hotelName,order.roomName,order.id].some(value => String(value || '').toLowerCase().includes(query)));
        const list = document.querySelector('#all-order-list');
        list.innerHTML = `<p class="muted">共 ${matches.length} 笔匹配订单</p>`;
        if (!matches.length) list.innerHTML += '<div class="empty">没有匹配的订单。</div>';
        matches.forEach(order => {
          const card = document.createElement('article'); card.className = 'stay-card';
          card.innerHTML = `<header><div><span class="eyebrow">ORDER ${escapeHtml(order.id)}</span><h3>${escapeHtml(order.hotelName)} · ${escapeHtml(order.roomName)}</h3></div><span class="badge">${escapeHtml(statusNames[order.status] || order.status)}</span></header><p class="muted">${escapeHtml(order.hotelAddress)}</p><div class="stay-facts"><span>1 间 · 1 晚 · 可住 ${escapeHtml(order.capacity)} 人</span><strong>${escapeHtml(money(order.amount))}</strong></div><p class="muted">创建时间：${escapeHtml(displayTime(order.createTime))}</p><div class="order-actions"><button class="secondary" data-action="detail">查看详情</button>${order.status === 'PENDING_PAYMENT'?'<button class="secondary" data-action="cancel">取消订单</button><button data-action="pay">继续付款</button>':''}</div>`;
          card.querySelector('[data-action="detail"]').onclick = async event => {
            const button = event.currentTarget; button.disabled = true;
            try {
              const current = await api(`/hotel/orders/${encodeURIComponent(order.id)}`);
              if (version !== viewVersion) return;
              document.querySelector('#detail h2').textContent = '订单详情';
              document.querySelector('#detail-body').innerHTML = [['订单编号',current.id],['订单状态',statusNames[current.status] || current.status],['酒店名称',current.hotelName],['酒店地址',current.hotelAddress],['联系电话',current.hotelPhone],['房型',current.roomName],['房型介绍',current.description],['规格','1 间 · 1 晚'],['订单金额',money(current.amount)],['房间号',current.roomNumber || '尚未分配'],['创建时间',displayTime(current.createTime)],['付款时间',displayTime(current.paidTime)],['入住时间',displayTime(current.checkInTime)],['退房时间',displayTime(current.checkOutTime)],['取消时间',current.cancelledTime?displayTime(current.cancelledTime):'—'],['退款原因',current.refundReason || '—'],['退款时间',current.refundTime?displayTime(current.refundTime):'—']].map(([key,value]) => `<div class="detail-row"><span class="muted">${key}</span><span>${escapeHtml(value)}</span></div>`).join('');
              document.querySelector('#detail').showModal();
            } catch(error) { if (version === viewVersion) message.textContent = error.message; }
            finally { button.disabled = false; }
          };
          const pay = card.querySelector('[data-action="pay"]');
          if (pay) pay.onclick = () => { sessionStorage.setItem('pendingOrderId', order.id); paymentView(order.id); };
          const cancel = card.querySelector('[data-action="cancel"]');
          if (cancel) cancel.onclick = async () => {
            cancel.disabled = true; if (pay) pay.disabled = true;
            try {
              await api(`/hotel/orders/${encodeURIComponent(order.id)}/cancel`, {method:'POST'});
              if (sessionStorage.getItem('pendingOrderId') === order.id) sessionStorage.removeItem('pendingOrderId');
              if (version === viewVersion) { navigate('orders'); message.className = 'notice success'; message.textContent = '订单已取消。'; }
            } catch(error) { if (version === viewVersion) message.textContent = error.message; }
            finally { cancel.disabled = false; if (pay) pay.disabled = false; }
          };
          list.appendChild(card);
        });
      };
      render(); select.onchange = render;
      document.querySelector('#order-filters').onsubmit = event => { event.preventDefault(); render(); };
      document.querySelector('#refresh-orders').onclick = () => navigate('orders');
      const ends = orders.filter(order => order.status === 'AWAITING_CHECK_IN').map(order => stayEndMillis(order.checkOutTime)).filter(Number.isFinite);
      if (ends.length) stayExpiryTimer = setTimeout(() => { if (version === viewVersion) navigate('orders'); }, Math.min(2147483647, Math.max(1000, Math.min(...ends) - Date.now() + 100)));
    } catch(error) {
      if (version !== viewVersion) return;
      content.innerHTML = '<div class="empty">订单加载失败。</div><button id="retry-orders">重试</button>';
      message.textContent = error.message; document.querySelector('#retry-orders').onclick = () => navigate('orders');
    }
  }
  function vipSummary(status) {
    return `<div class="vip-summary"><div class="vip-summary-top"><strong>${status.level?'V'+status.level:'普通会员'}</strong><span>累计有效模拟消费 ${escapeHtml(money(status.spending))}</span></div><progress class="vip-progress" value="${status.progress}" max="100" aria-label="VIP升级进度"></progress><p>${status.nextThreshold == null?'已达 V10，尊享最高等级福利':`距离 V${status.level+1} 还需 ${escapeHtml(money(status.remaining))}（门槛 ${escapeHtml(money(status.nextThreshold))}）`}</p></div>`;
  }
  function watchVip(update) {
    clearInterval(vipRefreshTimer);
    const version = viewVersion; let busy = false;
    const refresh = async () => {
      if (busy || document.hidden || version !== viewVersion) return;
      busy = true;
      try { const status = await api('/hotel/vip'); if (version === viewVersion) update(status); }
      catch(error) { if (version === viewVersion) message.textContent = '会员进度刷新失败：' + error.message; }
      finally { busy = false; }
    };
    vipRefreshTimer = setInterval(refresh,15000);
  }
  async function vipView() {
    const version = viewVersion; let claiming = false;
    content.innerHTML = '<div class="empty">正在加载会员权益…</div>';
    const render = status => {
      if (version !== viewVersion || claiming) return;
      content.innerHTML = `<section id="vip-panel"><div class="vip-hero"><div><span class="eyebrow">YUNQI MEMBERSHIP</span><h2>每一份体验，都让礼遇升级</h2>${vipSummary(status)}</div><aside class="vip-week"><span>本周福利 · V${status.level}</span><strong>${status.weeklyTickets}<small> 张免费抽奖券</small></strong><p>现有抽奖券：${status.tickets} 张</p><button id="claim-vip" ${status.canClaim?'':'disabled'}>${status.claimed?'本周已领取':status.level?'领取本周福利':'升级 V1 后可领取'}</button><p class="muted">下次重置：${escapeHtml(status.nextReset)} 00:00</p></aside></div><div class="stay-heading"><h2>V1—V10 每周福利</h2><button id="refresh-vip" class="secondary">刷新进度</button></div><div class="vip-benefits">${status.benefits.map(benefit => `<article class="vip-benefit ${status.level === benefit.level?'current-tier':''}"><strong>V${benefit.level}</strong><p>累计消费 ${escapeHtml(money(benefit.threshold))}</p><h3>${benefit.weeklyTickets} 张</h3><span>每周免费抽奖券</span><p class="muted">${status.level === benefit.level?'当前等级':status.level > benefit.level?'已达成':'待解锁'}</p></article>`).join('')}</div><div class="vip-rules"><h3>成长与领取规则</h3><p>累计消费为酒店已付款且未退款订单，加灵宠付费抽奖及培养补品的消费金额。免费券抽奖不增加消费，退款后等级和进度会重新计算。</p><p>每周一 00:00（北京时间）重置领取资格，按领取时等级领取一份福利，不能逐级叠领。本周已领取后升级，新增等级福利下周生效。已领取的券不回收、不过期，孵化抽奖自动优先使用。</p><p>所有消费与福利均为演示数据，不产生真实扣款。进度每 15 秒自动刷新，也可手动刷新。</p></div></section>`;
      document.querySelector('#refresh-vip').onclick = () => navigate('vip');
      document.querySelector('#claim-vip').onclick = async event => {
        const button = event.currentTarget; button.disabled = true; claiming = true;
        try {
          const updated = await api('/hotel/vip/claim',{method:'POST'});
          claiming = false;
          if (version === viewVersion) { render(updated); message.className = 'notice success'; message.textContent = '本周福利已领取，免费抽奖券已到账。'; }
        } catch(error) { if (version === viewVersion) { message.textContent = error.message; button.disabled = false; } }
        finally { claiming = false; }
      };
    };
    try { render(await api('/hotel/vip')); if (version === viewVersion) watchVip(render); }
    catch(error) {
      if (version !== viewVersion) return;
      content.innerHTML = '<div class="empty">会员信息加载失败。</div><button id="retry-vip">重试</button>';
      message.textContent = error.message; document.querySelector('#retry-vip').onclick = () => navigate('vip');
    }
  }
  function renderWallet(wallet) {
    const target = document.querySelector('#profile-wallet'); if (!target) return;
    const version = viewVersion;
    const labels = {RECHARGE:'模拟充值',PET_SUPPLEMENT:'购买补品',PET_REWARD:'灵宠寻宝'};
    target.innerHTML = `<section class="account-balance"><div><span class="eyebrow">ACCOUNT BALANCE</span><h3>我的余额</h3><strong>${escapeHtml(money(wallet.balance))}</strong></div><p class="muted">用于培养商店消费，充值及寻宝均为模拟余额，不可提现。</p><form id="recharge-form"><label for="recharge-amount">模拟充值金额</label><div class="filters"><input type="number" id="recharge-amount" min="1" max="100000" step="0.01" required placeholder="1～100000 元" aria-label="充值金额"><button>确认模拟充值</button></div><div class="notice" role="status" aria-live="polite"></div></form><details><summary>最近余额明细</summary>${wallet.transactions.length?wallet.transactions.map(tx => `<div class="wallet-row"><span>${escapeHtml(labels[tx.type] || tx.type)}</span><strong>${Number(tx.amount)>0?'+':''}${escapeHtml(money(tx.amount))}</strong><small>${escapeHtml(displayTime(tx.created_time))}</small></div>`).join(''):'<p class="muted">暂无余额记录</p>'}</details></section>`;
    target.querySelector('form').onsubmit = async event => {
      event.preventDefault(); const button = event.target.querySelector('button'); const notice = event.target.querySelector('.notice');
      const amount = target.querySelector('input').value; button.disabled = true; notice.textContent = '';
      const key = 'recharge-' + sessionStorage.getItem('username') + '-' + amount;
      try {
        let requestId = sessionStorage.getItem(key); if (!requestId) { requestId = crypto.randomUUID(); sessionStorage.setItem(key,requestId); }
        const updated = await api('/hotel/wallet/recharge',{method:'POST',body:JSON.stringify({requestId,amount})});
        sessionStorage.removeItem(key);
        if (version === viewVersion) { renderWallet(updated); const success = document.querySelector('#recharge-form .notice'); success.className = 'notice success'; success.textContent = '模拟充值成功，余额已到账。'; }
      } catch(error) { notice.textContent = error.message; }
      finally { button.disabled = false; }
    };
  }
  async function profileView() {
    const version = viewVersion;
    content.innerHTML = '<div class="empty">正在加载个人资料…</div>';
    try {
      const [profile, membership, wallet] = await Promise.all([api('/hotel/profile'),api('/hotel/vip'),api('/hotel/wallet')]);
      if (version !== viewVersion) return;
      content.innerHTML = `<div class="profile-layout"><section><div class="profile-summary"><div class="profile-avatar" aria-hidden="true">${escapeHtml((profile.nickname || profile.username || '我').slice(0,1))}</div><div><h2>${escapeHtml(profile.username)}</h2><p class="muted">${profile.role === 'ADMIN'?'管理员':'普通用户'} · 账号正常</p></div></div><div id="profile-vip"></div><div id="profile-wallet"></div><p class="muted">注册时间：${escapeHtml(displayTime(profile.createTime))}</p><form id="profile-form"><h3>基本资料</h3><label for="profile-nickname">昵称</label><input id="profile-nickname" maxlength="50" placeholder="设置你的昵称" autocomplete="nickname"><label for="profile-phone">联系电话</label><input id="profile-phone" type="tel" maxlength="20" placeholder="填写联系电话" autocomplete="tel"><button>保存资料</button><div class="notice" role="status" aria-live="polite"></div></form></section><section class="password-section"><h2>修改密码</h2><p class="muted">请输入原密码验证身份。新密码至少 8 个字符，最多 72 字节。</p><form id="password-form"><label for="old-password">原密码</label><input id="old-password" type="password" autocomplete="current-password" required><label for="new-password">新密码</label><input id="new-password" type="password" minlength="8" autocomplete="new-password" required><label for="confirm-password">确认新密码</label><input id="confirm-password" type="password" minlength="8" autocomplete="new-password" required><button>修改密码</button><div class="notice" role="status" aria-live="polite"></div></form></section></div>`;
      document.querySelector('#profile-vip').innerHTML = vipSummary(membership);
      watchVip(status => { const target = document.querySelector('#profile-vip'); if (target) target.innerHTML = vipSummary(status); });
      renderWallet(wallet);
      document.querySelector('#profile-nickname').value = profile.nickname || '';
      document.querySelector('#profile-phone').value = profile.phone || '';
      document.querySelector('#profile-form').onsubmit = async event => {
        event.preventDefault(); const button = event.target.querySelector('button'); const notice = event.target.querySelector('.notice');
        notice.textContent = ''; notice.className = 'notice'; button.disabled = true;
        try {
          const saved = await api('/hotel/profile', {method:'PUT',body:JSON.stringify({nickname:document.querySelector('#profile-nickname').value,phone:document.querySelector('#profile-phone').value})});
          notice.className = 'notice success'; notice.textContent = '个人资料已保存。';
          document.querySelector('#account').textContent = saved.nickname || saved.username;
          if (version === viewVersion) {
            document.querySelector('.profile-avatar').textContent = (saved.nickname || saved.username || '我').slice(0,1);
          }
        } catch(error) { notice.textContent = error.message; }
        finally { button.disabled = false; }
      };
      document.querySelector('#password-form').onsubmit = async event => {
        event.preventDefault(); const form = event.target; const button = form.querySelector('button'); const notice = form.querySelector('.notice');
        notice.textContent = ''; notice.className = 'notice';
        const oldPassword = document.querySelector('#old-password').value;
        const newPassword = document.querySelector('#new-password').value;
        if (newPassword !== document.querySelector('#confirm-password').value) { notice.textContent = '两次输入的新密码不一致'; return; }
        if (new TextEncoder().encode(newPassword).length > 72) { notice.textContent = '新密码不能超过 72 字节'; return; }
        button.disabled = true;
        try {
          await api('/hotel/profile/password', {method:'POST',body:JSON.stringify({oldPassword,newPassword})});
          form.reset(); notice.className = 'notice success'; notice.textContent = '密码修改成功。';
        } catch(error) { notice.textContent = error.message; }
        finally { button.disabled = false; }
      };
    } catch(error) {
      if (version !== viewVersion) return;
      content.innerHTML = '<div class="empty">个人资料加载失败。</div><button id="retry-profile">重试</button>';
      message.textContent = error.message; document.querySelector('#retry-profile').onclick = () => navigate('profile');
    }
  }
  function openRefundDialog(order) {
    const version = viewVersion;
    const dialog = document.createElement('dialog'); dialog.className = 'refund-dialog';
    dialog.setAttribute('aria-labelledby','refund-dialog-title');
    dialog.innerHTML = `<h2 id="refund-dialog-title">选择退款原因</h2><p class="muted">${escapeHtml(order.hotelName)} · ${escapeHtml(order.roomName)}</p><p>退款金额：<strong>${escapeHtml(money(order.amount))}</strong></p><form><label for="refund-reason">退款原因</label><select id="refund-reason" required><option value="">请选择原因</option>${['行程有变','订错酒店或房型','重复购买','价格原因','其他原因'].map(reason => `<option>${reason}</option>`).join('')}</select><p class="muted">确认后完成模拟全额退款，该订单不会返回待入住。</p><div class="dialog-actions"><button type="button" class="secondary" id="cancel-refund">取消</button><button type="submit">确认退款</button></div><div class="notice" role="status" aria-live="polite"></div></form>`;
    document.body.appendChild(dialog); dialog.addEventListener('close', () => dialog.remove());
    const cancel = dialog.querySelector('#cancel-refund'); cancel.onclick = () => dialog.close();
    dialog.querySelector('form').onsubmit = async event => {
      event.preventDefault();
      const submit = event.target.querySelector('[type="submit"]'); const notice = dialog.querySelector('.notice');
      submit.disabled = true; cancel.disabled = true; submit.textContent = '正在退款…'; notice.textContent = '';
      const preventEscape = event => event.preventDefault(); dialog.addEventListener('cancel', preventEscape);
      try {
        await api(`/hotel/orders/${encodeURIComponent(order.id)}/refund`, {method:'POST',body:JSON.stringify({reason:dialog.querySelector('select').value})});
        dialog.close();
        if (version === viewVersion) { navigate('refunds'); message.className = 'notice success'; message.textContent = `模拟退款成功，金额 ${money(order.amount)}。`; }
      } catch(error) { notice.textContent = error.message; }
      finally { submit.disabled = false; cancel.disabled = false; submit.textContent = '确认退款'; dialog.removeEventListener('cancel',preventEscape); }
    };
    dialog.showModal();
  }
  const rows = (list, total, page, size) => {
    document.querySelector('#matches').textContent = total;
    document.querySelector('#results').innerHTML = list.length ? `<div class="table-wrap"><table><thead><tr><th>酒店 ID</th><th>酒店名称</th><th>地址</th><th>联系电话</th><th>操作</th></tr></thead><tbody>${list.map(h => `<tr><td>#${escapeHtml(h.id)}</td><td><b>${escapeHtml(h.name)}</b></td><td>${escapeHtml(h.address)}</td><td>${escapeHtml(h.phone)}</td><td><button class="secondary" data-id="${escapeHtml(h.id)}">查看详情</button></td></tr>`).join('')}</tbody></table></div>` : '<div class="empty">没有找到匹配的酒店，请调整查询条件。</div>';
    document.querySelectorAll('[data-id]').forEach(button => button.onclick = async () => {
      const version = viewVersion;
      button.disabled = true; message.textContent = '';
      try {
        const result = await api(`/hotel/search?${new URLSearchParams({keyword:'#'+button.dataset.id,page:'0',size:'1'})}`);
        if (version !== viewVersion || !button.isConnected) return;
        const h = result.items?.find(hotel => String(hotel.id) === button.dataset.id);
        if (!h) throw new Error('该酒店不存在');
        document.querySelector('#detail h2').textContent = '酒店详情';
        document.querySelector('#detail-body').innerHTML = [['酒店 ID',h.id],['酒店名称',h.name],['地址',h.address],['联系电话',h.phone]].map(([key,value]) => `<div class="detail-row"><span class="muted">${key}</span><span>${escapeHtml(value)}</span></div>`).join('');
        document.querySelector('#detail').showModal();
      } catch(e) { message.textContent = e.message; } finally { button.disabled = false; }
    });
    if (total > size) {
      const pager = document.createElement('nav'); pager.className = 'search-pagination'; pager.setAttribute('aria-label', '酒店列表分页');
      pager.innerHTML = `<button type="button" class="secondary" ${page === 0?'disabled':''}>上一页</button><span>第 ${page + 1} / ${Math.ceil(total / size)} 页 · 共 ${total} 家</span><button type="button" class="secondary" ${(page + 1) * size >= total?'disabled':''}>下一页</button>`;
      const buttons = pager.querySelectorAll('button');
      buttons[0].onclick = () => loadHotels(hotelQuery, page - 1);
      buttons[1].onclick = () => loadHotels(hotelQuery, page + 1);
      document.querySelector('#results').appendChild(pager);
    }
  };
  function hotelView() {
    content.innerHTML = '<h2>酒店列表</h2><form class="filters" id="search"><input aria-label="搜索酒店" id="keyword" placeholder="酒店名称、地址、完整电话或 ID"><button>查询</button><button type="button" class="secondary" id="refresh">刷新列表</button></form><div id="results"></div><div class="panel-footer">支持关键词搜索 · 电话和酒店 ID 精确匹配</div>';
    document.querySelector('#keyword').value = hotelQuery;
    document.querySelector('#search').onsubmit = event => { event.preventDefault(); loadHotels(document.querySelector('#keyword').value.trim(), 0); };
    document.querySelector('#refresh').onclick = () => loadHotels(hotelQuery, 0);
    loadHotels(hotelQuery, hotelPage);
  }
  async function loadHotels(keyword = hotelQuery, page = hotelPage) {
    const current = ++hotelSearchRequest; const version = viewVersion;
    hotelQuery = keyword; hotelPage = page; message.textContent = '';
    const buttons = [...document.querySelectorAll('#search button')]; buttons.forEach(button => button.disabled = true);
    const results = document.querySelector('#results');
    if (results) results.innerHTML = '<div class="empty">正在查询酒店…</div>';
    try {
      const data = await api(`/hotel/search?${new URLSearchParams({keyword,page:String(page),size:'10'})}`);
      if (!Array.isArray(data.items) || !Number.isFinite(data.total)) throw new Error('酒店查询响应格式不正确');
      if (current !== hotelSearchRequest) return;
      if (!keyword) document.querySelector('#total').textContent = data.total;
      if (version === viewVersion && results?.isConnected) rows(data.items, data.total, data.page, data.size);
    } catch(error) {
      if (current === hotelSearchRequest && version === viewVersion && results?.isConnected) {
        message.textContent = error.message; results.innerHTML = '<div class="empty">查询失败，请点击刷新列表重试。</div>';
      }
    } finally { if (current === hotelSearchRequest) buttons.forEach(button => button.disabled = false); }
  }
  async function petView() {
    const version = viewVersion;
    const elements = {
      METAL:{label:'金',color:'#b5a66e',file:'metal',name:'001',description:'全身金属的机器人，安静而可靠。'},
      WOOD:{label:'木',color:'#66a88e',file:'wood',name:'芽枝鹿',description:'青色的小鹿，枝角间长着嫩芽。'},
      WATER:{label:'水',color:'#70b8d4',file:'water',name:'泡泡',description:'透明又圆滚滚的史莱姆，像一颗水泡。'},
      FIRE:{label:'火',color:'#df9164',file:'fire',name:'焰羽雀',description:'羽毛带着火焰色彩的温暖小雀。'},
      EARTH:{label:'土',color:'#a18e70',file:'earth',name:'岩块鼹鼠',description:'背着岩块的小鼹鼠，沉稳又可爱。'}
    };
    const rewards = [5,10,15,20,30,50];
    let pendingKey = 'pet-spin-' + (sessionStorage.getItem('username') || 'current');
    content.innerHTML = '<div class="empty">正在唤醒你的五行灵蛋…</div>';
    try {
      let pet = await api('/hotel/pet');
      pendingKey += '-' + pet.generation;
      if (version !== viewVersion) return;
      const element = elements[pet.element];
      if (!element) throw new Error('未知的灵宠属性');
      content.innerHTML = '<section class="hatch-layout"><div class="hatch-home" style="--element-color:' + element.color + '"><div class="hatch-top"><span class="eyebrow">FIVE ELEMENTS · SPIRIT COMPANION</span><span class="badge" id="pet-rarity">' + element.label + '系</span></div><div class="hatch-progress-label"><strong id="hatch-state"></strong><span id="hatch-number"></span></div><progress id="hatch-progress" max="100" aria-label="孵化进度"></progress><div class="hatch-stage" id="hatch-stage"></div><div class="pet-grade" id="pet-grade"><span class="eyebrow">灵宠评级</span><strong id="pet-grade-value" class="grade-large"></strong></div><h2 id="hatch-title"></h2><p id="hatch-description" class="muted"></p><div class="pet-ledger"><span id="pet-spins"></span><span id="pet-cost"></span><span id="pet-generation"></span></div><div class="rehatch-actions"><button class="secondary" id="restart-pet" hidden>重新孵化</button><p class="muted" id="restart-note" hidden>重新孵化将清空当前培养度并替换灵宠；余额与累计消费保留。</p></div></div><section class="hatch-lottery"><div class="eyebrow">A LITTLE LUCK, A LITTLE GROWTH</div><h2>孵化能量转盘</h2><p class="muted">优先使用会员免费抽奖券，无券时每次模拟消费 100 元。</p><div class="wheel-shell"><div class="wheel-pointer" aria-hidden="true"></div><div class="hatch-wheel" id="hatch-wheel" aria-hidden="true">' + rewards.map((reward,index) => '<span class="wheel-reward" style="--angle:' + index*60 + 'deg">+' + reward + '</span>').join('') + '</div><div class="wheel-center" aria-hidden="true">孵</div></div><button id="spin-pet" class="spin-button">100 元 · 模拟抽奖</button><div id="spin-result" class="notice" role="status" aria-live="polite"></div><p class="muted lottery-rules">孵化时有 50% 概率变异为 SSS 隐藏款；否则按 C/B/A/S/SS 自动评级。普通评级概率分别为 40%/30%/18%/9%/3%（在未变异情况下）。+5、+10、+15、+20、+30、+50 六档能量等概率抽取（各 1/6），进度最高为 100。</p><div class="simulation-note"><strong>模拟消费，不会实际扣款</strong><p>进度及抽奖记录保存在账号中，刷新或重新登录仍保留。孵化完成后停止抽奖。</p></div></section></section><section id="pet-training"></section><section class="pet-catalog"><h2>五行灵宠图鉴</h2><div class="pet-species">' + Object.entries(elements).map(([code,e]) => '<article class="species-card ' + (code === pet.element?'current-species':'') + '"><span class="badge">' + e.label + '系 · C～SS</span><img src="/assets/pets/' + e.file + '.svg" alt="' + e.description + '" loading="lazy"><h3>' + e.name + '</h3><p class="muted">' + e.description + '</p></article>').join('') + '<article class="species-card hidden-species"><span class="rarity-label">SSS 级</span><div class="secret-pet" aria-label="未知隐藏款">?</div><h3>隐藏款</h3><p class="muted">孵化时 50% 概率变异</p></article></div></section>';
      let trainingBusy = false;
      const paintTraining = state => {
        if (version !== viewVersion) return;
        document.querySelector('.hatch-lottery').style.display = pet.hatched ? 'none' : '';
        document.querySelector('.hatch-layout').classList.toggle('is-training',state.enabled);
        const resultNotice = document.querySelector('#spin-result');
        if (pet.hatched && resultNotice.closest('.hatch-lottery')) {
          document.querySelector('.hatch-home').appendChild(resultNotice);
        }
        const progress = document.querySelector('#hatch-progress');
        progress.max = state.enabled ? 10000 : 100;
        progress.value = state.enabled ? state.value : pet.hatchValue;
        progress.setAttribute('aria-label',state.enabled ? '灵宠培养进度' : '孵化进度');
        document.querySelector('#hatch-number').textContent = state.enabled ? state.value + ' / 10000' : pet.hatchValue + ' / 100';
        document.querySelector('#hatch-state').textContent = state.enabled ? '培养度 · ' + state.stage : '孵化完成';
        const target = document.querySelector('#pet-training');
        target.innerHTML = '<div class="training-heading"><div><span class="eyebrow">GROW TOGETHER</span><h2>灵宠培养 · ' + escapeHtml(state.stage) + '</h2></div><span class="badge">余额 ' + escapeHtml(money(state.balance)) + '</span></div><div class="training-stages">陌生 0 → 相识 1000 → 熟悉 3000 → 友好 6000 → 形影不离 10000</div><div id="training-notice" class="notice" role="status" aria-live="polite"></div>' + (!state.enabled?'<p class="muted">进入培养阶段后开放补品商店，购买的补品将立即喂给当前灵宠。</p><button id="start-training">进入培养阶段</button>':'<div class="stay-heading"><h3>培养商店</h3><button class="secondary" id="training-recharge">去充值</button></div><p class="muted">从账户余额扣款，购买后立即喂食。培养度最高10000，溢出不结转；满级后停止购买。</p><div class="supplement-grid">' + state.shop.map(item => '<article class="supplement-card"><span class="supplement-icon" aria-hidden="true">✦</span><h3>' + escapeHtml(item.name) + '</h3><p class="muted">' + escapeHtml(item.description) + '</p><span class="badge">培养度 +' + item.gain + '</span><div class="supplement-buy"><strong>' + money(item.price) + '</strong><button data-supplement="' + escapeHtml(item.code) + '" ' + (state.value >= 10000?'disabled':'') + '>购买并喂食</button></div></article>').join('') + '</div><div class="training-reward"><div><h3>形影不离 · 灵宠回馈</h3><p class="muted">达到10000培养度后，每天可获得一次100～1000元随机模拟余额，直接入账。每天00:00（北京时间）重置，重新孵化不重置当天领取资格。</p></div><button id="pet-reward" ' + (state.value<10000 || state.rewardClaimed?'disabled':'') + '>' + (state.rewardClaimed?'今日已领取':state.value<10000?'形影不离后解锁':'让灵宠为我寻宝') + '</button></div>');
        const action = async (path, payload, button) => {
          if (trainingBusy) return; trainingBusy = true;
          target.querySelectorAll('button').forEach(b => b.disabled = true);
          const notice = target.querySelector('#training-notice'); notice.textContent = '';
          try {
            const result = await api('/hotel/pet/training/' + path,{method:'POST',body:JSON.stringify(payload)});
            if (path === 'buy') sessionStorage.removeItem(pendingKey + '-buy-' + payload.code);
            if (version !== viewVersion) return;
            paintTraining(result.training || result);
            const updatedNotice = document.querySelector('#training-notice'); updatedNotice.className = 'notice success';
            updatedNotice.textContent = path === 'reward'?'寻宝成功，' + money(result.amount) + ' 已存入余额。':path === 'buy'?'补品已喂食，培养度已提升。':'已进入培养阶段，补品商店已开放。';
          } catch(error) {
            if (version === viewVersion) { paintTraining(state); document.querySelector('#training-notice').textContent = error.message; }
          } finally { trainingBusy = false; }
        };
        const start = target.querySelector('#start-training');
        if (start) start.onclick = () => action('start',{generation:pet.generation},start);
        const recharge = target.querySelector('#training-recharge'); if (recharge) recharge.onclick = () => navigate('profile');
        target.querySelectorAll('[data-supplement]').forEach(button => button.onclick = () => {
          const key = pendingKey + '-buy-' + button.dataset.supplement;
          let requestId = sessionStorage.getItem(key); if (!requestId) { requestId = crypto.randomUUID(); sessionStorage.setItem(key,requestId); }
          action('buy',{requestId,generation:pet.generation,code:button.dataset.supplement},button);
        });
        const reward = target.querySelector('#pet-reward'); if (reward) reward.onclick = () => action('reward',{generation:pet.generation},reward);
      };
      const refreshTraining = async () => {
        const target = document.querySelector('#pet-training');
        if (!pet.hatched) { target.innerHTML = ''; return; }
        try { const state = await api('/hotel/pet/training'); if (version === viewVersion && !trainingBusy) paintTraining(state); }
        catch(error) { if (version === viewVersion) { target.textContent = '培养信息加载失败：' + error.message; const retry = document.createElement('button'); retry.className='secondary'; retry.textContent='重试'; retry.onclick=refreshTraining; target.appendChild(retry); } }
      };
      const render = () => {
        document.querySelector('.hatch-lottery').style.display = pet.hatched ? 'none' : '';
        document.querySelector('.hatch-layout').classList.toggle('hatching-complete',pet.hatched);
        const resultNotice = document.querySelector('#spin-result');
        if (pet.hatched && resultNotice.closest('.hatch-lottery')) {
          document.querySelector('.hatch-home').appendChild(resultNotice);
        }
        document.querySelector('#pet-grade').style.display = pet.hatched?'grid':'none';
        const grade = document.querySelector('#pet-grade-value');
        grade.textContent = pet.rarity || ''; grade.className = 'grade-large grade-' + (pet.rarity || '').toLowerCase();
        document.querySelector('#restart-pet').hidden = !pet.hatched;
        document.querySelector('#restart-note').hidden = !pet.hatched;
        document.querySelector('#pet-generation').textContent = '第 ' + pet.generation + ' 轮孵化';
        document.querySelector('.hatch-layout').classList.toggle('dragon-hatched', pet.mutated);
        const rarityBadge = document.querySelector('#pet-rarity');
        rarityBadge.textContent = pet.hatched?(pet.mutated?'隐藏变异 · SSS 级':element.label + '系 · ' + pet.rarity + ' 级'):element.label + '系 · 待评级';
        rarityBadge.classList.toggle('rarity-sss', pet.mutated);
        document.querySelector('#hatch-progress').max = 100;
        document.querySelector('#hatch-progress').value = pet.hatchValue;
        document.querySelector('#hatch-number').textContent = pet.hatchValue + ' / 100';
        document.querySelector('#hatch-state').textContent = pet.hatched?'孵化完成':'正在孵化';
        document.querySelector('#hatch-title').textContent = pet.hatched?pet.name:element.label + '系灵蛋';
        document.querySelector('#hatch-description').textContent = pet.hatched?(pet.mutated?'冰霜巨龙 · 寒冰铸甲，晶翼遮天。稀有变异已觉醒。':element.description):'你的灵蛋已与你结缘，集满能量后将孵化为' + element.name + '，也有机会发生隐藏变异。';
        document.querySelector('#pet-spins').textContent = '累计抽奖 ' + pet.spinCount + ' 次';
        document.querySelector('#pet-cost').textContent = '累计模拟消费 ¥' + pet.simulatedTotalCost;
        document.querySelector('#hatch-stage').innerHTML = pet.hatched?'<img class="hatched-character ' + (pet.mutated?'hidden-dragon':'') + '" src="/assets/pets/' + (pet.mutated?'frost-dragon':element.file) + '.svg" alt="' + (pet.mutated?'SSS级冰霜巨龙，棱角分明的冰晶龙甲与巨大双翼':element.description) + '">':'<div class="spirit-egg" role="img" aria-label="' + element.label + '系灵蛋"><span class="egg-mark">' + element.label + '</span><span class="egg-glint"></span></div><div class="egg-shadow"></div>';
        refreshTraining();
        const button = document.querySelector('#spin-pet'); button.disabled = pet.hatched; button.textContent = pet.hatched?'已孵化 · 期待与你相伴':pet.freeTickets > 0?'使用免费抽奖券（剩 ' + pet.freeTickets + ' 张）':'100 元 · 模拟抽奖';
      };
      render();
      document.querySelector('#restart-pet').onclick = async event => {
        if (!window.confirm('重新孵化将替换当前灵宠，清空本轮孵化进度、培养度和评级，随机获得一颗新蛋。累计消费记录保留，是否继续？')) return;
        const button = event.currentTarget; button.disabled = true; button.textContent = '正在生成新灵蛋…';
        const resetKey = pendingKey + '-restart';
        const notice = document.querySelector('#spin-result'); notice.textContent = '';
        try {
          let requestId = sessionStorage.getItem(resetKey);
          if (!requestId) { requestId = crypto.randomUUID(); sessionStorage.setItem(resetKey,requestId); }
          await api('/hotel/pet/restart', {method:'POST',body:JSON.stringify({requestId,generation:pet.generation})});
          sessionStorage.removeItem(resetKey); sessionStorage.removeItem(pendingKey);
          if (version === viewVersion) { navigate('pet'); message.className = 'notice success'; message.textContent = '新的灵蛋已生成，可以开始新一轮孵化。'; }
        } catch(error) { if (version === viewVersion) { notice.className = 'notice'; notice.textContent = error.message + '，可点击重新孵化重试。'; } }
        finally { button.disabled = false; button.textContent = '重新孵化'; }
      };
      let rotation = 0;
      document.querySelector('#spin-pet').onclick = async event => {
        const button = event.currentTarget; const notice = document.querySelector('#spin-result');
        window.petEffects?.prepare();
        button.disabled = true; button.textContent = '正在抽取孵化能量…'; notice.textContent = ''; notice.className = 'notice';
        let result;
        try {
          let requestId = sessionStorage.getItem(pendingKey);
          if (!requestId) { requestId = crypto.randomUUID(); sessionStorage.setItem(pendingKey,requestId); }
          result = await api('/hotel/pet/spin', {method:'POST',body:JSON.stringify({requestId,generation:pet.generation})});
          sessionStorage.removeItem(pendingKey);
          if (version !== viewVersion) return;
          const wheel = document.querySelector('#hatch-wheel');
          const target = (360 - result.segmentIndex * 60) % 360;
          rotation += 1800 + (target - rotation % 360 + 360) % 360;
          const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
          wheel.style.transform = 'rotate(' + rotation + 'deg)';
          await new Promise(resolve => setTimeout(resolve, reduced ? 0 : 3200));
          if (version !== viewVersion) return;
          const newlySummonedDragon = !pet.hatched && result.pet.hatched && result.pet.mutated;
          pet = result.pet; render();
          if (newlySummonedDragon) await window.petEffects?.summon();
          if (version !== viewVersion) return;
          notice.className = 'notice success'; notice.textContent = (result.simulatedCost === 0?'已使用 1 张免费抽奖券，':'本次模拟消费 100 元，') + '获得 +' + result.reward + ' 孵化值！' + (pet.hatched?(pet.mutated?'隐藏变异！SSS 级冰霜巨龙觉醒！':'孵化成功！' + pet.rarity + ' 级' + pet.name + '诞生了！'):'当前进度 ' + pet.hatchValue + ' / 100。');
        } catch(error) {
          if (version !== viewVersion) return;
          notice.textContent = error.message + '。可重试同一请求，不会重复记账。';
          try { const latest = await api('/hotel/pet'); if (version === viewVersion) { if (latest.generation !== pet.generation) { sessionStorage.removeItem(pendingKey); navigate('pet'); } else { pet = latest; render(); } } } catch { /* Keep the current view available for retry. */ }
        } finally {
          if (version === viewVersion) { button.disabled = pet.hatched; button.textContent = pet.hatched?'已孵化 · 期待与你相伴':pet.freeTickets > 0?'使用免费抽奖券（剩 ' + pet.freeTickets + ' 张）':'100 元 · 模拟抽奖'; }
        }
      };
    } catch(error) {
      if (version !== viewVersion) return;
      content.innerHTML = '<div class="empty">灵宠页面加载失败。</div><button id="retry-pet">重试</button>';
      message.textContent = error.message; document.querySelector('#retry-pet').onclick = () => navigate('pet');
    }
  }
  function businessView() {
    content.innerHTML = '<div class="business-intro"><div><div class="eyebrow">EXPLORE & STAY</div><h2>找到酒店，了解每一种入住选择</h2><p class="muted">搜索酒店后，直接浏览房型图片、每晚价格与入住人数。</p></div></div><form id="business-search" class="filters"><input id="business-keyword" aria-label="搜索酒店" placeholder="输入酒店名称、地址、电话或酒店 ID"><button>搜索酒店</button></form><div id="business-hotels" aria-live="polite"></div><section id="business-rooms" aria-live="polite"></section>';
    const list = document.querySelector('#business-hotels');
    const detail = document.querySelector('#business-rooms');
    let requestId = 0; let searchId = 0;
    let imageMapPromise;
    const getImageMap = () => imageMapPromise ||= fetch('/assets/room-images.json', {credentials:'omit'})
      .then(response => { if (!response.ok) throw new Error(); return response.json(); }).catch(() => ({}));
    async function showHotel(hotel, button) {
      const current = ++requestId;
      detail.innerHTML = '<div class="empty">正在加载酒店房型…</div>';
      list.querySelectorAll('button').forEach(b => b.classList.toggle('selected', b === button));
      try {
        const [rooms, imageMap] = await Promise.all([api(`/hotel/rooms?hotelId=${encodeURIComponent(hotel.id)}`), getImageMap()]);
        if (current !== requestId || !detail.isConnected) return;
        if (!Array.isArray(rooms)) throw new Error('房型列表响应格式不正确');
        detail.innerHTML = `<header class="selected-hotel"><div><span class="eyebrow">HOTEL #${escapeHtml(hotel.id)}</span><h2>${escapeHtml(hotel.name)}</h2><p class="muted">${escapeHtml(hotel.address)} · 联系电话 ${escapeHtml(hotel.phone)}</p></div><span class="badge">${rooms.length} 个房型</span></header>${rooms.length ? '<div class="room-cards"></div>' : '<div class="empty">该酒店尚未添加房型，请选择其他酒店。</div>'}`;
        const cards = detail.querySelector('.room-cards');
        rooms.forEach(room => {
          const type = /家庭|套房/.test(room.name) ? 'family' : /双床/.test(room.name) ? 'twin' : 'double';
          const configured = imageMap && typeof imageMap[String(room.id)] === 'string' ? imageMap[String(room.id)] : '';
          // Only same-origin static assets are accepted as configured room photos.
          const imageUrl = configured.startsWith('/assets/') && !configured.includes('..') && !configured.includes('\\') ? configured : '';
          const card = document.createElement('article'); card.className = 'room-card';
          card.innerHTML = `<div class="room-photo"><img loading="lazy" alt="${escapeHtml(room.name)}${imageUrl?'图片':'示意图'}"><span class="photo-label">${imageUrl?'房型图片':'房型示意图 · 非实拍'}</span></div><div class="room-card-body"><span class="eyebrow">ROOM #${escapeHtml(room.id)}</span><h3>${escapeHtml(room.name)}</h3><span class="badge">最多入住 ${escapeHtml(room.capacity)} 人</span><p class="muted room-description">${escapeHtml(room.description || '暂无房型介绍')}</p><div class="room-price">${room.price == null?'价格待定':`<strong>¥${escapeHtml(Number(room.price).toFixed(2))}</strong><span> / 晚</span>`}</div></div>`;
          const img = card.querySelector('img');
          img.onerror = () => { img.onerror = null; img.src = `/assets/rooms/${type}.svg`; img.alt = `${room.name}示意图`; card.querySelector('.photo-label').textContent = '房型示意图 · 非实拍'; };
          img.src = imageUrl || `/assets/rooms/${type}.svg`;
          const purchase = document.createElement('button'); purchase.className = 'purchase-button'; purchase.textContent = '购买';
          const stock = document.createElement('p'); stock.className = 'muted'; stock.textContent = '正在查询库存…';
          card.querySelector('.room-card-body').appendChild(stock);
          card.querySelector('.room-price').appendChild(purchase);
          let available = 0;
          let purchasing = false;
          purchase.disabled = true;
          async function refreshStock() {
            try {
              const inventory = await api('/hotel/orders/inventory?roomTypeId=' + encodeURIComponent(room.id));
              available = inventory.available;
              stock.textContent = available > 0 ? '当前可售 ' + available + ' 间' : '当前已售罄';
              purchase.disabled = purchasing || available <= 0 || room.price == null || Number(room.price) <= 0;
            } catch (error) {
              stock.textContent = error.message;
              purchase.disabled = true;
            }
          }
          purchase.onclick = async () => {
            if (purchasing) return;
            purchasing = true; purchase.disabled = true; purchase.textContent = '正在创建订单…'; message.textContent = '';
            const version = viewVersion;
            const pendingKey = 'room-order-' + sessionStorage.getItem('username') + '-' + room.id;
            let bookingRequestId = sessionStorage.getItem(pendingKey);
            if (!bookingRequestId) {
              bookingRequestId = crypto.randomUUID();
              sessionStorage.setItem(pendingKey, bookingRequestId);
            }
            try {
              const order = await api('/hotel/orders', {method:'POST',body:JSON.stringify({roomTypeId:room.id,requestId:bookingRequestId})});
              sessionStorage.removeItem(pendingKey);
              sessionStorage.setItem('pendingOrderId',order.id);
              if (version === viewVersion) paymentView(order.id);
            } catch(error) { if (version === viewVersion) message.textContent = error.message; }
            finally {
              purchasing = false; purchase.textContent = '购买';
              if (card.isConnected) await refreshStock();
            }
          };
          cards.appendChild(card);
          refreshStock();
        });
      } catch(error) {
        if (current !== requestId || !detail.isConnected) return;
        detail.innerHTML = '<div class="empty"></div>'; detail.firstElementChild.textContent = `${error.message}，请重新选择酒店重试。`;
      }
    }
    async function search(page = 0, keyword = document.querySelector('#business-keyword').value.trim()) {
      const current = ++searchId; ++requestId;
      const button = document.querySelector('#business-search button'); button.disabled = true;
      list.innerHTML = '<div class="empty">正在搜索酒店…</div>'; detail.innerHTML = '';
      try {
        const data = await api(`/hotel/search?${new URLSearchParams({keyword,page:String(page),size:'10'})}`);
        if (current !== searchId || !list.isConnected) return;
        if (!Array.isArray(data.items) || !Number.isFinite(data.total)) throw new Error('酒店查询响应格式不正确');
        const matches = data.items;
        document.querySelector('#matches').textContent = data.total;
        if (!keyword) document.querySelector('#total').textContent = data.total;
        list.innerHTML = matches.length ? `<p class="muted">找到 ${data.total} 家酒店${data.total > 1?'，请选择酒店查看房型':''}</p><div class="hotel-options"></div>` : '<div class="empty">没有找到酒店，请尝试其他名称、地址、电话或 ID。</div>';
        matches.forEach(hotel => {
          const option = document.createElement('button'); option.type = 'button'; option.className = 'hotel-option';
          option.innerHTML = `<strong>${escapeHtml(hotel.name)}</strong><span>#${escapeHtml(hotel.id)} · ${escapeHtml(hotel.address)}</span><span>${escapeHtml(hotel.phone)}　→</span>`;
          option.onclick = () => showHotel(hotel, option); list.querySelector('.hotel-options').appendChild(option);
        });
        if (data.total > data.size) {
          const pager = document.createElement('nav'); pager.className = 'search-pagination'; pager.setAttribute('aria-label', '搜索结果分页');
          pager.innerHTML = `<button type="button" class="secondary" ${page === 0?'disabled':''}>上一页</button><span>第 ${page + 1} / ${Math.ceil(data.total / data.size)} 页 · 共 ${data.total} 家</span><button type="button" class="secondary" ${(page + 1) * data.size >= data.total?'disabled':''}>下一页</button>`;
          const controls = pager.querySelectorAll('button'); controls[0].onclick = () => search(page - 1, keyword); controls[1].onclick = () => search(page + 1, keyword);
          list.appendChild(pager);
        }
        if (data.total === 1) await showHotel(matches[0], list.querySelector('button'));
      } catch(error) { if (current === searchId && list.isConnected) { list.innerHTML = '<div class="empty"></div>'; list.firstElementChild.textContent = `${error.message}，请重新搜索。`; } }
      finally { if (current === searchId) button.disabled = false; }
    }
    document.querySelector('#business-search').onsubmit = event => { event.preventDefault(); search(); };
    list.innerHTML = '<div class="empty">输入酒店信息开始搜索。搜索到唯一酒店时，将自动展示全部房型。</div>';
    const pendingId = sessionStorage.getItem('pendingOrderId');
    if (pendingId) {
      const resume = document.createElement('button'); resume.className = 'secondary resume-payment'; resume.textContent = '继续支付上次未付款订单 →';
      resume.onclick = () => paymentView(pendingId); content.prepend(resume);
    }
  }
  function navigate(view) {
    window.petEffects?.stop();
    ++viewVersion;
    clearTimeout(stayExpiryTimer);
    clearInterval(vipRefreshTimer);
    message.textContent = ''; message.className = 'notice';
    document.querySelectorAll('[data-view]').forEach(b => b.classList.toggle('active',b.dataset.view === view));
    heading({hotels:'酒店管理',business:'办理酒店业务',stays:'待入住',refunds:'退款',orders:'我的订单',profile:'个人中心',pet:'我的灵宠',vip:'VIP会员'}[view], {hotels:'查看酒店资料，快速找到你需要的信息。',business:'搜索酒店，浏览房型图片与价格。',stays:'查看已付款的酒店与房型订单。',refunds:'选择退款原因，查看退款进度与结果。',orders:'每一笔入住选择，都有记录可查。',profile:'管理个人资料与账号密码。',pet:'工作间隙，和你的小伙伴相伴片刻。',vip:'消费累积成长，每周领取专属福利。'}[view]);
    document.querySelector('.stats').style.display = ['profile','orders','pet','vip'].includes(view)?'none':'';
    if (view === 'vip') vipView(); else if (view === 'pet') petView(); else if (view === 'profile') profileView(); else if (view === 'orders') ordersView(); else if (view === 'refunds') refundsView(); else if (view === 'stays') staysView(); else if (view === 'business') businessView(); else hotelView();
  }
  document.querySelectorAll('[data-view]').forEach(button => button.onclick = () => navigate(button.dataset.view));
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && document.querySelector('#stay-list')) navigate('stays');
    if (!document.hidden && document.querySelector('#refund-list')) navigate('refunds');
    if (!document.hidden && document.querySelector('#all-order-list')) navigate('orders');
    if (!document.hidden && document.querySelector('#vip-panel')) navigate('vip');
  });
  hotelView();
  try { await api('/hotel/auth/me'); } catch(e) { message.textContent = e.message; }
}
if (page === 'dashboard') dashboard(); else authPage();






