type Listener = (event: FakeEvent) => void;

export interface VirtualPageMetrics {
  pageNumber: number;
  offsetTop: number;
  height: number;
}

export class FakeEvent {
  type: string;
  bubbles: boolean;
  cancelable: boolean;
  defaultPrevented = false;
  cancelBubble = false;
  target: FakeNode | null = null;
  currentTarget: FakeNode | null = null;

  constructor(type: string, init?: { bubbles?: boolean; cancelable?: boolean }) {
    this.type = type;
    this.bubbles = Boolean(init?.bubbles);
    this.cancelable = Boolean(init?.cancelable);
  }

  stopPropagation(): void {
    this.cancelBubble = true;
  }

  preventDefault(): void {
    if (this.cancelable) {
      this.defaultPrevented = true;
    }
  }
}

export class FakeWheelEvent extends FakeEvent {
  deltaX: number;
  deltaY: number;

  constructor(
    type: string,
    init?: { bubbles?: boolean; cancelable?: boolean; deltaX?: number; deltaY?: number }
  ) {
    super(type, init);
    this.deltaX = init?.deltaX ?? 0;
    this.deltaY = init?.deltaY ?? 0;
  }
}

export class FakeNode {
  nodeType: number;
  nodeName: string;
  parentNode: FakeNode | null = null;
  childNodes: FakeNode[] = [];
  ownerDocument: FakeDocument | null = null;
  private bubbleListeners = new Map<string, Set<Listener>>();
  private captureListeners = new Map<string, Set<Listener>>();

  constructor(nodeType: number, nodeName: string, ownerDocument: FakeDocument | null) {
    this.nodeType = nodeType;
    this.nodeName = nodeName;
    this.ownerDocument = ownerDocument;
  }

  get firstChild(): FakeNode | null {
    return this.childNodes[0] ?? null;
  }

  get lastChild(): FakeNode | null {
    return this.childNodes[this.childNodes.length - 1] ?? null;
  }

  get nextSibling(): FakeNode | null {
    if (!this.parentNode) return null;
    const idx = this.parentNode.childNodes.indexOf(this);
    return idx >= 0 && idx + 1 < this.parentNode.childNodes.length
      ? this.parentNode.childNodes[idx + 1]
      : null;
  }

  get previousSibling(): FakeNode | null {
    if (!this.parentNode) return null;
    const idx = this.parentNode.childNodes.indexOf(this);
    return idx > 0 ? this.parentNode.childNodes[idx - 1] : null;
  }

  get isConnected(): boolean {
    let cur: FakeNode | null = this;
    while (cur) {
      if (cur === this.ownerDocument?.body || cur === this.ownerDocument?.documentElement) {
        return true;
      }
      cur = cur.parentNode;
    }
    return false;
  }

  get textContent(): string {
    return this.childNodes.map((c) => c.textContent).join('');
  }

  set textContent(value: string) {
    this.childNodes = [];
    if (value && this.ownerDocument) {
      this.appendChild(this.ownerDocument.createTextNode(value));
    }
  }

  appendChild<T extends FakeNode>(child: T): T {
    if (child.nodeType === 11) {
      for (const nested of [...child.childNodes]) {
        child.removeChild(nested);
        this.appendChild(nested);
      }
      return child;
    }
    if (child.parentNode) {
      child.parentNode.removeChild(child);
    }
    child.parentNode = this;
    child.ownerDocument = this.ownerDocument;
    this.childNodes.push(child);
    return child;
  }

  insertBefore<T extends FakeNode>(newChild: T, refChild: FakeNode | null): T {
    if (!refChild) {
      return this.appendChild(newChild);
    }
    if (newChild.nodeType === 11) {
      for (const nested of [...newChild.childNodes]) {
        newChild.removeChild(nested);
        this.insertBefore(nested, refChild);
      }
      return newChild;
    }
    if (newChild.parentNode) {
      newChild.parentNode.removeChild(newChild);
    }
    const idx = this.childNodes.indexOf(refChild);
    newChild.parentNode = this;
    newChild.ownerDocument = this.ownerDocument;
    if (idx === -1) {
      this.childNodes.push(newChild);
    } else {
      this.childNodes.splice(idx, 0, newChild);
    }
    return newChild;
  }

  removeChild<T extends FakeNode>(child: T): T {
    const idx = this.childNodes.indexOf(child);
    if (idx !== -1) {
      this.childNodes.splice(idx, 1);
      child.parentNode = null;
    }
    return child;
  }

  contains(other: FakeNode | null): boolean {
    let cur = other;
    while (cur) {
      if (cur === this) return true;
      cur = cur.parentNode;
    }
    return false;
  }

  addEventListener(type: string, listener: Listener, options?: boolean | AddEventListenerOptions): void {
    const capture = typeof options === 'boolean' ? options : Boolean(options?.capture);
    const map = capture ? this.captureListeners : this.bubbleListeners;
    let set = map.get(type);
    if (!set) {
      set = new Set();
      map.set(type, set);
    }
    set.add(listener);
  }

  removeEventListener(type: string, listener: Listener, options?: boolean | AddEventListenerOptions): void {
    const capture = typeof options === 'boolean' ? options : Boolean(options?.capture);
    const map = capture ? this.captureListeners : this.bubbleListeners;
    map.get(type)?.delete(listener);
  }

  dispatchEvent(event: FakeEvent): boolean {
    event.target = this;
    const ancestors: FakeNode[] = [];
    let p = this.parentNode;
    while (p) {
      ancestors.unshift(p);
      p = p.parentNode;
    }

    // 1. Capture phase (top -> parent -> target)
    for (const node of [...ancestors, this]) {
      if (event.cancelBubble) break;
      event.currentTarget = node;
      const set = node.captureListeners.get(event.type);
      if (set) {
        for (const listener of [...set]) {
          listener(event);
        }
      }
    }

    // 2. Target + Bubble phase
    const targetAndUp: FakeNode[] = event.bubbles ? [this, ...ancestors.reverse()] : [this];
    for (const node of targetAndUp) {
      if (event.cancelBubble) break;
      event.currentTarget = node;
      const set = node.bubbleListeners.get(event.type);
      if (set) {
        for (const listener of [...set]) {
          listener(event);
        }
      }
    }

    return !event.defaultPrevented;
  }
}

export class FakeText extends FakeNode {
  data: string;

  constructor(data: string, ownerDocument: FakeDocument) {
    super(3, '#text', ownerDocument);
    this.data = data;
  }

  override get textContent(): string {
    return this.data;
  }

  override set textContent(value: string) {
    this.data = value;
  }

  get nodeValue(): string {
    return this.data;
  }

  set nodeValue(value: string | null) {
    this.data = value ?? '';
  }
}

export class FakeComment extends FakeNode {
  data: string;

  constructor(data: string, ownerDocument: FakeDocument) {
    super(8, '#comment', ownerDocument);
    this.data = data;
  }

  override get textContent(): string {
    return '';
  }

  override set textContent(_v: string) {
    // Comment text doesn't affect DOM textContent
  }
}

export class FakeClassList {
  private owner: FakeElement;
  constructor(owner: FakeElement) {
    this.owner = owner;
  }
  private getTokens(): string[] {
    return this.owner.className.split(/\s+/).filter(Boolean);
  }
  add(...tokens: string[]): void {
    const set = new Set(this.getTokens());
    tokens.forEach((t) => set.add(t));
    this.owner.className = [...set].join(' ');
  }
  remove(...tokens: string[]): void {
    const removeSet = new Set(tokens);
    this.owner.className = this.getTokens().filter((t) => !removeSet.has(t)).join(' ');
  }
  contains(token: string): boolean {
    return this.getTokens().includes(token);
  }
}

export class FakeElement extends FakeNode {
  tagName: string;
  attributes = new Map<string, string>();
  style: Record<string, string> = {};
  classList: FakeClassList;
  scrollTop = 0;
  scrollLeft = 0;
  clientHeight = 0;
  clientWidth = 400;
  scrollHeight = 0;
  scrollWidth = 400;
  private _value = '';
  disabled = false;
  __rectOverride?: () => { top: number; bottom: number; left: number; right: number; width: number; height: number };
  __scrollIntoViewHook?: (options?: ScrollIntoViewOptions) => void;

  constructor(tagName: string, ownerDocument: FakeDocument) {
    super(1, tagName.toUpperCase(), ownerDocument);
    this.tagName = tagName.toUpperCase();
    this.classList = new FakeClassList(this);
  }

  get value(): string {
    return this._value;
  }

  set value(val: string) {
    this._value = String(val);
  }

  get className(): string {
    return this.attributes.get('class') ?? '';
  }

  set className(val: string) {
    this.attributes.set('class', val);
  }

  get id(): string {
    return this.attributes.get('id') ?? '';
  }

  set id(val: string) {
    this.attributes.set('id', val);
  }

  get type(): string {
    return this.attributes.get('type') ?? (this.tagName === 'INPUT' ? 'text' : '');
  }

  set type(val: string) {
    this.attributes.set('type', String(val));
  }

  setAttribute(name: string, value: string): void {
    this.attributes.set(name, String(value));
  }

  getAttribute(name: string): string | null {
    return this.attributes.get(name) ?? null;
  }

  hasAttribute(name: string): boolean {
    return this.attributes.has(name);
  }

  removeAttribute(name: string): void {
    this.attributes.delete(name);
  }

  getBoundingClientRect(): {
    top: number;
    bottom: number;
    left: number;
    right: number;
    width: number;
    height: number;
  } {
    if (this.__rectOverride) {
      return this.__rectOverride();
    }
    return { top: 0, bottom: this.clientHeight, left: 0, right: 390, width: 390, height: this.clientHeight };
  }

  scrollIntoView(options?: ScrollIntoViewOptions): void {
    if (this.__scrollIntoViewHook) {
      this.__scrollIntoViewHook(options);
    }
  }

  querySelectorAll(selector: string): FakeElement[] {
    const tag = selector.toLowerCase();
    const out: FakeElement[] = [];
    const visit = (node: FakeNode) => {
      for (const child of node.childNodes) {
        if (child instanceof FakeElement) {
          if (child.tagName.toLowerCase() === tag) {
            out.push(child);
          }
          visit(child);
        }
      }
    };
    visit(this);
    return out;
  }

  querySelector(selector: string): FakeElement | null {
    return this.querySelectorAll(selector)[0] ?? null;
  }

  focus(): void {
    if (this.ownerDocument) {
      this.ownerDocument.activeElement = this;
    }
  }
}

export class FakeDocument extends FakeNode {
  documentElement: FakeElement;
  body: FakeElement;
  activeElement: FakeNode | null = null;
  defaultView: Record<string, unknown> | null = null;
  oninput: unknown = null;
  onchange: unknown = null;

  constructor() {
    super(9, '#document', null);
    this.ownerDocument = this;
    this.documentElement = new FakeElement('html', this);
    this.body = new FakeElement('body', this);
    this.appendChild(this.documentElement);
    this.documentElement.appendChild(this.body);
  }

  createElement(tag: string): FakeElement {
    return new FakeElement(tag, this);
  }

  createElementNS(_ns: string, tag: string): FakeElement {
    return new FakeElement(tag, this);
  }

  createTextNode(text: string): FakeText {
    return new FakeText(text, this);
  }

  createComment(text: string): FakeComment {
    return new FakeComment(text, this);
  }

  createDocumentFragment(): FakeNode {
    return new FakeNode(11, '#document-fragment', this);
  }
}

export class FakeLocalStorage {
  private data = new Map<string, string>();
  getItem(key: string): string | null {
    return this.data.has(key) ? this.data.get(key)! : null;
  }
  setItem(key: string, value: string): void {
    this.data.set(key, String(value));
  }
  removeItem(key: string): void {
    this.data.delete(key);
  }
  clear(): void {
    this.data.clear();
  }
}

export interface FakeDomEnvironment {
  document: FakeDocument;
  localStorage: FakeLocalStorage;
  observers: FakeIntersectionObserver[];
  resizeObservers: FakeResizeObserver[];
  flushAnimationFrames: () => void;
  advanceTimersBy: (ms: number) => void;
  getPendingTimerCount: () => number;
  dispatchWindowResize: () => void;
  cleanup: () => void;
}

export class FakeIntersectionObserver {
  callback: IntersectionObserverCallback;
  options?: IntersectionObserverInit;
  observed = new Set<FakeElement>();
  disconnected = false;

  constructor(callback: IntersectionObserverCallback, options?: IntersectionObserverInit) {
    this.callback = callback;
    this.options = options;
  }

  observe(el: FakeElement): void {
    this.observed.add(el);
  }

  unobserve(el: FakeElement): void {
    this.observed.delete(el);
  }

  disconnect(): void {
    this.disconnected = true;
    this.observed.clear();
  }

  takeRecords(): IntersectionObserverEntry[] {
    return [];
  }

  triggerAll(): void {
    if (this.disconnected) return;
    const entries = [...this.observed].map((el) => ({
      target: el as unknown as Element,
      isIntersecting: true,
      intersectionRatio: 0.5,
      boundingClientRect: el.getBoundingClientRect() as DOMRectReadOnly,
      intersectionRect: el.getBoundingClientRect() as DOMRectReadOnly,
      rootBounds: null,
      time: Date.now(),
    }));
    this.callback(entries, this as unknown as IntersectionObserver);
  }
}

export class FakeResizeObserver {
  callback: ResizeObserverCallback;
  observed = new Set<FakeElement>();
  disconnected = false;

  constructor(callback: ResizeObserverCallback) {
    this.callback = callback;
  }

  observe(el: FakeElement): void {
    this.observed.add(el);
  }

  unobserve(el: FakeElement): void {
    this.observed.delete(el);
  }

  disconnect(): void {
    this.disconnected = true;
    this.observed.clear();
  }

  triggerAll(): void {
    if (this.disconnected) return;
    const entries = [...this.observed].map((el) => {
      const rect = el.getBoundingClientRect();
      return {
        target: el as unknown as Element,
        contentRect: rect as DOMRectReadOnly,
        borderBoxSize: [{ inlineSize: rect.width, blockSize: rect.height }],
        contentBoxSize: [{ inlineSize: rect.width, blockSize: rect.height }],
        devicePixelContentBoxSize: [{ inlineSize: rect.width, blockSize: rect.height }],
      } as ResizeObserverEntry;
    });
    this.callback(entries, this as unknown as ResizeObserver);
  }
}

export function setFakeInputValue(inputEl: FakeElement, nextValue: string): void {
  const protoDescriptor = Object.getOwnPropertyDescriptor(FakeElement.prototype, 'value');
  if (protoDescriptor?.set) {
    protoDescriptor.set.call(inputEl, nextValue);
  } else {
    inputEl.value = nextValue;
  }
  inputEl.dispatchEvent(new FakeEvent('input', { bubbles: true }));
}

export function installFakeDomEnvironment(): FakeDomEnvironment {
  const doc = new FakeDocument();
  const storage = new FakeLocalStorage();
  const observers: FakeIntersectionObserver[] = [];
  const resizeObservers: FakeResizeObserver[] = [];
  const windowListeners = new Map<string, Set<Listener>>();

  let nextRafId = 1;
  const rafQueue = new Map<number, FrameRequestCallback>();

  const requestAnimationFramePolyfill = (cb: FrameRequestCallback): number => {
    const id = nextRafId++;
    rafQueue.set(id, cb);
    return id;
  };

  const cancelAnimationFramePolyfill = (id: number): void => {
    rafQueue.delete(id);
  };

  const flushAnimationFrames = (): void => {
    const pending = [...rafQueue.entries()];
    rafQueue.clear();
    for (const [, cb] of pending) {
      cb(Date.now());
    }
  };

  class TrackedObserver extends FakeIntersectionObserver {
    constructor(cb: IntersectionObserverCallback, opts?: IntersectionObserverInit) {
      super(cb, opts);
      observers.push(this);
    }
  }

  let nowMs = Date.now();
  let nextTimerId = 1;
  const timerQueue = new Map<number, { fireAtMs: number; callback: () => void }>();

  const setTimeoutPolyfill = (handler: TimerHandler, timeout = 0): number => {
    const id = nextTimerId++;
    const cb = typeof handler === 'function' ? () => handler() : () => undefined;
    timerQueue.set(id, { fireAtMs: nowMs + Math.max(0, timeout), callback: cb });
    return id;
  };

  const clearTimeoutPolyfill = (id?: number): void => {
    if (id !== undefined) {
      timerQueue.delete(id);
    }
  };

  const advanceTimersBy = (ms: number): void => {
    const targetMs = nowMs + Math.max(0, ms);
    while (true) {
      let nextEntry: [number, { fireAtMs: number; callback: () => void }] | null = null;
      for (const entry of timerQueue.entries()) {
        if (entry[1].fireAtMs <= targetMs) {
          if (!nextEntry || entry[1].fireAtMs < nextEntry[1].fireAtMs) {
            nextEntry = entry;
          }
        }
      }
      if (!nextEntry) break;
      const [id, item] = nextEntry;
      timerQueue.delete(id);
      nowMs = item.fireAtMs;
      item.callback();
    }
    nowMs = targetMs;
  };

  class TrackedResizeObserver extends FakeResizeObserver {
    constructor(cb: ResizeObserverCallback) {
      super(cb);
      resizeObservers.push(this);
    }
  }

  const addWindowListener = (type: string, listener: Listener): void => {
    let set = windowListeners.get(type);
    if (!set) {
      set = new Set();
      windowListeners.set(type, set);
    }
    set.add(listener);
  };

  const removeWindowListener = (type: string, listener: Listener): void => {
    windowListeners.get(type)?.delete(listener);
  };

  const dispatchWindowEvent = (event: FakeEvent): boolean => {
    const set = windowListeners.get(event.type);
    if (set) {
      for (const listener of [...set]) {
        listener(event);
      }
    }
    return !event.defaultPrevented;
  };

  const fakeWindow: Record<string, unknown> = {
    document: doc,
    localStorage: storage,
    setTimeout: setTimeoutPolyfill,
    clearTimeout: clearTimeoutPolyfill,
    requestAnimationFrame: requestAnimationFramePolyfill,
    cancelAnimationFrame: cancelAnimationFramePolyfill,
    addEventListener: addWindowListener,
    removeEventListener: removeWindowListener,
    dispatchEvent: dispatchWindowEvent,
    IntersectionObserver: TrackedObserver,
    ResizeObserver: TrackedResizeObserver,
    HTMLElement: FakeElement,
    HTMLIFrameElement: FakeElement,
    HTMLInputElement: FakeElement,
    HTMLTextAreaElement: FakeElement,
    HTMLSelectElement: FakeElement,
    HTMLButtonElement: FakeElement,
    SVGElement: FakeElement,
    Element: FakeElement,
    Node: FakeNode,
    Event: FakeEvent,
  };
  doc.defaultView = fakeWindow;

  const prev = {
    window: (globalThis as Record<string, unknown>).window,
    document: (globalThis as Record<string, unknown>).document,
    localStorage: (globalThis as Record<string, unknown>).localStorage,
    IntersectionObserver: (globalThis as Record<string, unknown>).IntersectionObserver,
    ResizeObserver: (globalThis as Record<string, unknown>).ResizeObserver,
    requestAnimationFrame: (globalThis as Record<string, unknown>).requestAnimationFrame,
    cancelAnimationFrame: (globalThis as Record<string, unknown>).cancelAnimationFrame,
    HTMLElement: (globalThis as Record<string, unknown>).HTMLElement,
    HTMLIFrameElement: (globalThis as Record<string, unknown>).HTMLIFrameElement,
    SVGElement: (globalThis as Record<string, unknown>).SVGElement,
    Element: (globalThis as Record<string, unknown>).Element,
    Node: (globalThis as Record<string, unknown>).Node,
    Event: (globalThis as Record<string, unknown>).Event,
    IS_REACT_ACT_ENVIRONMENT: (globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT,
  };

  Object.assign(globalThis, {
    window: fakeWindow,
    document: doc,
    localStorage: storage,
    IntersectionObserver: TrackedObserver,
    ResizeObserver: TrackedResizeObserver,
    requestAnimationFrame: requestAnimationFramePolyfill,
    cancelAnimationFrame: cancelAnimationFramePolyfill,
    HTMLElement: FakeElement,
    HTMLIFrameElement: FakeElement,
    SVGElement: FakeElement,
    Element: FakeElement,
    Node: FakeNode,
    Event: FakeEvent,
    IS_REACT_ACT_ENVIRONMENT: true,
  });

  return {
    document: doc,
    localStorage: storage,
    observers,
    resizeObservers,
    flushAnimationFrames,
    advanceTimersBy,
    getPendingTimerCount: () => timerQueue.size,
    dispatchWindowResize: () => {
      dispatchWindowEvent(new FakeEvent('resize', { bubbles: false }));
    },
    cleanup: () => {
      windowListeners.clear();
      timerQueue.clear();
      rafQueue.clear();
      Object.assign(globalThis, prev);
    },
  };
}
