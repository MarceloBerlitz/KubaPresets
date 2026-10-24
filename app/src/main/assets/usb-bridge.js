/* Polyfill de navigator.hid (WebHID) e navigator.usb (WebUSB) sobre a ponte nativa Android. */
(function () {
  'use strict';
  if (window.__kubaBridgeLoaded || !window.AndroidUsb) return;
  window.__kubaBridgeLoaded = true;

  var native = window.AndroidUsb;
  var pending = new Map();
  var seq = 1;

  function call(method, args) {
    return new Promise(function (resolve, reject) {
      var id = seq++;
      pending.set(id, { resolve: resolve, reject: reject });
      native.call(id, method, JSON.stringify(args || {}));
    });
  }

  window.__kubaResolve = function (id, ok, payload) {
    var p = pending.get(id);
    if (!p) return;
    pending.delete(id);
    if (ok) p.resolve(payload);
    else p.reject(new DOMException(payload.message || 'Error', payload.name || 'UnknownError'));
  };

  function u8(d) {
    if (d instanceof ArrayBuffer) return new Uint8Array(d);
    if (ArrayBuffer.isView(d)) return new Uint8Array(d.buffer, d.byteOffset, d.byteLength);
    return new Uint8Array(d || []);
  }
  function enc(d) {
    var u = u8(d), s = '';
    for (var i = 0; i < u.length; i++) s += String.fromCharCode(u[i]);
    return btoa(s);
  }
  function view(b64) {
    var bin = atob(b64), u = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
    return new DataView(u.buffer);
  }
  function fire(target, type, props) {
    var ev = new Event(type);
    Object.keys(props || {}).forEach(function (k) { ev[k] = props[k]; });
    try { target.dispatchEvent(ev); } catch (e) { console.error(e); }
    var h = target['on' + type];
    if (typeof h === 'function') { try { h.call(target, ev); } catch (e) { console.error(e); } }
  }

  /* ------------------------------------------------------------ WebHID */
  class HIDDevice extends EventTarget {
    constructor(info) {
      super();
      this._id = info.id;
      this.opened = false;
      this.vendorId = info.vendorId;
      this.productId = info.productId;
      this.productName = info.productName;
      this.collections = info.collections || [];
      this.oninputreport = null;
    }
    async open() {
      if (this.opened) return;
      await call('hidOpen', { id: this._id });
      this.opened = true;
    }
    async close() {
      if (!this.opened) return;
      await call('hidClose', { id: this._id });
      this.opened = false;
    }
    async forget() { try { await this.close(); } catch (e) {} }
    async sendReport(reportId, data) {
      await call('hidSend', { id: this._id, reportId: reportId, data: enc(data) });
    }
    async sendFeatureReport(reportId, data) {
      await call('hidSendFeature', { id: this._id, reportId: reportId, data: enc(data) });
    }
    async receiveFeatureReport(reportId) {
      var r = await call('hidGetFeature', { id: this._id, reportId: reportId });
      return view(r.data);
    }
  }

  class HID extends EventTarget {
    constructor() { super(); this._devs = new Map(); this.onconnect = null; this.ondisconnect = null; }
    _wrap(info) {
      var d = this._devs.get(info.id);
      if (!d) { d = new HIDDevice(info); this._devs.set(info.id, d); }
      return d;
    }
    async getDevices() {
      var r = await call('list', { api: 'hid' });
      return r.devices.map(this._wrap, this);
    }
    async requestDevice(options) {
      var o = options || {};
      var r = await call('request', { api: 'hid', filters: o.filters || [], exclusionFilters: o.exclusionFilters || [] });
      var devs = r.devices.map(this._wrap, this);
      if (!devs.length) throw new DOMException('No device selected.', 'NotFoundError');
      return devs;
    }
    _dispatch(type, p) {
      var self = this;
      if (type === 'inputreport') {
        var d = this._devs.get(p.id);
        if (d) fire(d, 'inputreport', { device: d, reportId: p.reportId, data: view(p.data) });
      } else if (type === 'connect') {
        p.devices.forEach(function (i) { var d = self._wrap(i); fire(self, 'connect', { device: d }); });
      } else if (type === 'disconnect') {
        Array.from(this._devs.entries()).forEach(function (e) {
          if (e[0].indexOf(p.name + '|') === 0) {
            e[1].opened = false;
            self._devs.delete(e[0]);
            fire(self, 'disconnect', { device: e[1] });
          }
        });
      }
    }
  }

  /* ------------------------------------------------------------ WebUSB */
  class USBDevice {
    constructor(i) {
      var self = this;
      this._id = i.id;
      ['deviceClass', 'deviceSubclass', 'deviceProtocol', 'vendorId', 'productId',
        'deviceVersionMajor', 'deviceVersionMinor', 'deviceVersionSubminor',
        'manufacturerName', 'productName', 'serialNumber'].forEach(function (k) { self[k] = i[k]; });
      this.usbVersionMajor = 2; this.usbVersionMinor = 0; this.usbVersionSubminor = 0;
      this.configurations = i.configurations.map(function (c) {
        return {
          configurationValue: c.configurationValue,
          configurationName: c.configurationName,
          interfaces: c.interfaces.map(function (f) {
            return { interfaceNumber: f.interfaceNumber, claimed: false, alternates: f.alternates, alternate: f.alternates[0] };
          })
        };
      });
      this.configuration = null;
      this.opened = false;
    }
    _iface(n) {
      if (!this.configuration) throw new DOMException('No configuration selected.', 'InvalidStateError');
      var f = this.configuration.interfaces.find(function (x) { return x.interfaceNumber === n; });
      if (!f) throw new DOMException('Interface not found.', 'NotFoundError');
      return f;
    }
    async open() {
      var r = await call('usbOpen', { id: this._id });
      this.opened = true;
      var v = r.configurationValue;
      this.configuration = this.configurations.find(function (c) { return c.configurationValue === v; }) || null;
    }
    async close() { await call('usbClose', { id: this._id }); this.opened = false; }
    async forget() { try { await this.close(); } catch (e) {} }
    async selectConfiguration(v) {
      await call('usbSelectConfig', { id: this._id, configurationValue: v });
      this.configuration = this.configurations.find(function (c) { return c.configurationValue === v; });
    }
    async claimInterface(n) {
      var f = this._iface(n);
      await call('usbClaim', { id: this._id, interfaceNumber: n });
      f.claimed = true; f.alternate = f.alternates[0];
    }
    async releaseInterface(n) {
      var f = this._iface(n);
      await call('usbRelease', { id: this._id, interfaceNumber: n });
      f.claimed = false;
    }
    async selectAlternateInterface(n, alt) {
      var f = this._iface(n);
      await call('usbAlt', { id: this._id, interfaceNumber: n, alternateSetting: alt });
      f.alternate = f.alternates.find(function (a) { return a.alternateSetting === alt; });
    }
    async controlTransferIn(setup, length) {
      var r = await call('usbCtlIn', Object.assign({ id: this._id, length: length }, setup));
      return { status: r.status, data: view(r.data) };
    }
    async controlTransferOut(setup, data) {
      var a = Object.assign({ id: this._id }, setup);
      if (data) a.data = enc(data);
      var r = await call('usbCtlOut', a);
      return { status: r.status, bytesWritten: r.bytesWritten };
    }
    async transferIn(ep, length) {
      var r = await call('usbIn', { id: this._id, endpointNumber: ep, length: length });
      return { status: r.status, data: view(r.data) };
    }
    async transferOut(ep, data) {
      var r = await call('usbOut', { id: this._id, endpointNumber: ep, data: enc(data) });
      return { status: r.status, bytesWritten: r.bytesWritten };
    }
    async clearHalt() {}
    async reset() { await call('usbReset', { id: this._id }); }
  }

  class USB extends EventTarget {
    constructor() { super(); this._devs = new Map(); this.onconnect = null; this.ondisconnect = null; }
    _wrap(info) {
      var d = this._devs.get(info.id);
      if (!d) { d = new USBDevice(info); this._devs.set(info.id, d); }
      return d;
    }
    async getDevices() {
      var r = await call('list', { api: 'usb' });
      return r.devices.map(this._wrap, this);
    }
    async requestDevice(options) {
      var o = options || {};
      var r = await call('request', { api: 'usb', filters: o.filters || [], exclusionFilters: o.exclusionFilters || [] });
      return r.devices.map(this._wrap, this)[0];
    }
    _dispatch(type, p) {
      var self = this;
      if (type === 'connect') {
        p.devices.forEach(function (i) { fire(self, 'connect', { device: self._wrap(i) }); });
      } else if (type === 'disconnect') {
        var d = this._devs.get(p.name);
        if (d) { d.opened = false; this._devs.delete(p.name); fire(this, 'disconnect', { device: d }); }
      }
    }
  }

  var hidMgr = new HID();
  var usbMgr = new USB();
  window.__kubaEvent = function (kind, type, payload) {
    try { (kind === 'hid' ? hidMgr : usbMgr)._dispatch(type, payload); } catch (e) { console.error(e); }
  };

  function define(name, value) {
    try { Object.defineProperty(navigator, name, { value: value, configurable: true }); } catch (e) {}
  }
  define('hid', hidMgr);
  define('usb', usbMgr);
  window.HIDDevice = HIDDevice;
  window.USBDevice = USBDevice;
})();
