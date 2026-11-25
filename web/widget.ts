(() => {
  type WidgetOptions = {
    origin: string;
    siteId?: string;
    color?: string;
    position?: 'right' | 'left';
    title?: string;
  };

  class NewCrispWidget {
    private host: HTMLDivElement | null = null;
    private iframe: HTMLIFrameElement | null = null;
    private badge: HTMLSpanElement | null = null;
    private panel: HTMLDivElement | null = null;
    private button: HTMLButtonElement | null = null;
    private open = false;
    private mounted = false;
    private origin = '';
    private siteId = '';

    mount(options: WidgetOptions) {
      if (this.mounted) return;
      this.mounted = true;
      this.origin = options.origin.replace(/\/$/, '');
      this.siteId = options.siteId ?? '';
      const color = options.color || '#2563eb';
      const side = options.position === 'left' ? 'left' : 'right';

      this.host = document.createElement('div');
      this.host.id = 'newcrisp-widget-root';
      const shadow = this.host.attachShadow({ mode: 'open' });

      const style = document.createElement('style');
      style.textContent = `
        :host { all: initial; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
        .nc-wrap { position: fixed; bottom: 20px; ${side}: 20px; z-index: 2147483000; display: flex; flex-direction: column; align-items: ${side === 'left' ? 'flex-start' : 'flex-end'}; gap: 12px; }
        .nc-panel { width: 390px; height: 600px; max-width: calc(100vw - 24px); max-height: calc(100vh - 96px); border-radius: 16px; overflow: hidden; box-shadow: 0 18px 48px rgba(15, 23, 42, 0.22); background: #fff; border: 1px solid rgba(15, 23, 42, 0.08); transform-origin: bottom ${side}; transition: opacity 0.18s ease, transform 0.2s ease; opacity: 0; transform: translateY(12px) scale(0.96); pointer-events: none; display: none; }
        .nc-panel.open { display: block; opacity: 1; transform: translateY(0) scale(1); pointer-events: auto; }
        .nc-frame { width: 100%; height: 100%; border: 0; display: block; background: #fff; }
        .nc-btn { position: relative; width: 58px; height: 58px; border-radius: 50%; border: 0; background: ${color}; color: #fff; cursor: pointer; box-shadow: 0 8px 24px rgba(37, 99, 235, 0.36); display: flex; align-items: center; justify-content: center; transition: transform 0.16s ease, box-shadow 0.16s ease; }
        .nc-btn:hover { transform: scale(1.06); box-shadow: 0 12px 28px rgba(37, 99, 235, 0.44); }
        .nc-btn svg { width: 26px; height: 26px; fill: currentColor; }
        .nc-badge { position: absolute; top: -3px; right: -3px; min-width: 20px; height: 20px; padding: 0 6px; border-radius: 999px; background: #ef4444; color: #fff; font-size: 11px; font-weight: 700; display: none; align-items: center; justify-content: center; box-sizing: border-box; border: 2px solid #fff; }
        .nc-badge.show { display: inline-flex; }
        @media (max-width: 520px) {
          .nc-wrap { bottom: 14px; ${side}: 14px; }
          .nc-panel { width: calc(100vw - 28px); height: calc(100vh - 96px); }
        }
      `;
      shadow.appendChild(style);

      const wrap = document.createElement('div');
      wrap.className = 'nc-wrap';

      this.panel = document.createElement('div');
      this.panel.className = 'nc-panel';
      this.panel.setAttribute('role', 'dialog');
      this.panel.setAttribute('aria-label', options.title || '在线客服');

      this.button = document.createElement('button');
      this.button.type = 'button';
      this.button.className = 'nc-btn';
      this.button.setAttribute('aria-label', '打开在线客服');
      this.button.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 2H4a2 2 0 0 0-2 2v18l4-4h14a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2zm-3 11H7v-2h10v2zm0-3H7V8h10v2z"/></svg>`;

      this.badge = document.createElement('span');
      this.badge.className = 'nc-badge';
      this.button.appendChild(this.badge);
      this.button.addEventListener('click', () => this.toggle());

      wrap.appendChild(this.panel);
      wrap.appendChild(this.button);
      shadow.appendChild(wrap);
      document.body.appendChild(this.host);

      window.addEventListener('message', (event) => {
        if (event.origin !== this.origin || !event.data || typeof event.data !== 'object') return;
        if (event.data.type === 'newcrisp:unread' && typeof event.data.count === 'number') {
          this.setUnread(event.data.count);
        } else if (event.data.type === 'newcrisp:close') {
          this.close();
        }
      });
    }

    private ensureFrame() {
      if (this.iframe || !this.panel || !this.siteId) return;
      const iframe = document.createElement('iframe');
      iframe.className = 'nc-frame';
      iframe.title = '在线客服';
      iframe.src = `${this.origin}/embed/${encodeURIComponent(this.siteId)}`;
      iframe.addEventListener('load', () => this.pushPageContext());
      this.panel.appendChild(iframe);
      this.iframe = iframe;
    }

    pushPageContext() {
      if (!this.iframe?.contentWindow) return;
      try {
        this.iframe.contentWindow.postMessage({ type: 'newcrisp:context', pageUrl: window.location.href }, this.origin);
      } catch { /* ignore cross-origin transient error */ }
    }

    setUnread(count: number) {
      if (!this.badge) return;
      if (count > 0 && !this.open) {
        this.badge.textContent = count > 99 ? '99+' : String(count);
        this.badge.classList.add('show');
      } else {
        this.badge.classList.remove('show');
      }
    }

    toggle() {
      if (this.open) this.close();
      else this.show();
    }

    show() {
      this.ensureFrame();
      this.open = true;
      this.panel?.classList.add('open');
      this.badge?.classList.remove('show');
      this.button?.setAttribute('aria-label', '收起在线客服');
      this.pushPageContext();
    }

    close() {
      this.open = false;
      this.panel?.classList.remove('open');
      this.button?.setAttribute('aria-label', '打开在线客服');
    }
  }

  const instance = new NewCrispWidget();
  (window as unknown as { NewCrispWidget?: NewCrispWidget }).NewCrispWidget = instance;

  const currentScript = document.currentScript as HTMLScriptElement | null;
  const siteId = currentScript?.dataset?.site;
  if (currentScript && siteId) {
    const scriptOrigin = new URL(currentScript.src, window.location.href).origin;
    const boot = () => instance.mount({
      origin: scriptOrigin,
      siteId,
      color: currentScript.dataset.color,
      position: currentScript.dataset.position === 'left' ? 'left' : 'right',
    });
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot, { once: true });
    else boot();
  }
})();
