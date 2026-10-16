
import * as d2FzbTpqcy1zdHJpbmc from './risk-engine.js-builtins.mjs';

const wasmJsTag = WebAssembly.JSTag;
const wasmTag = wasmJsTag ?? new WebAssembly.Tag({ parameters: ['externref'] });

// Placed here to give access to it from externals (js_code)
let wasmExports;

if (typeof process !== 'undefined' && process.release.name === 'node') {
    function doNotUseRequire() {
        throw new Error("Do not use top-level require. Prefer to use JS import or define your own require instead. Read more: https://kotl.in/r9txlt")
    }

    var require = new Proxy((function() {}), {
        apply(target, thisArg, argumentsList) {
            if (globalThis.require != null) {
                return globalThis.require.apply(thisArg, argumentsList);
            } else {
                doNotUseRequire();
            }
        },
        get(target, prop, receiver) {
            if (globalThis.require != null) {
                return Reflect.get(globalThis.require, prop);
            } else {
                doNotUseRequire();
            }
        },
    });
}

export function setWasmExports(exports) {
    wasmExports = exports;
}

const cachedJsObjects = new WeakMap();
function getCachedJsObject(ref, ifNotCached) {
    if (typeof ref !== 'object' && typeof ref !== 'function') return ifNotCached;
    const cached = cachedJsObjects.get(ref);
    if (cached !== void 0) return cached;
    cachedJsObjects.set(ref, ifNotCached);
    return ifNotCached;
}

const js_code = {
    'kotlin.createJsError' : (message, cause) => new Error(message, { cause }),
    'kotlin.wasm.internal.jsThrow' : wasmTag === wasmJsTag ? (e) => { throw e; } : () => {},
    'kotlin.wasm.internal.getJsEmptyString' : () => '',
    'kotlin.wasm.internal.externrefToString' : (ref) => String(ref),
    'kotlin.wasm.internal.externrefEquals' : (lhs, rhs) => lhs === rhs,
    'kotlin.wasm.internal.externrefHashCode' : 
    (() => {
    const dataView = new DataView(new ArrayBuffer(8));
    function numberHashCode(obj) {
        if ((obj | 0) === obj) {
            return obj | 0;
        } else {
            dataView.setFloat64(0, obj, true);
            return (dataView.getInt32(0, true) * 31 | 0) + dataView.getInt32(4, true) | 0;
        }
    }

    const hashCodes = new WeakMap();
    function getObjectHashCode(obj) {
        const res = hashCodes.get(obj);
        if (res === undefined) {
            const POW_2_32 = 4294967296;
            const hash = (Math.random() * POW_2_32) | 0;
            hashCodes.set(obj, hash);
            return hash;
        }
        return res;
    }

    function getStringHashCode(str) {
        var hash = 0;
        for (var i = 0; i < str.length; i++) {
            var code  = str.charCodeAt(i);
            hash  = (hash * 31 + code) | 0;
        }
        return hash;
    }

    return (obj) => {
        if (obj == null) {
            return 0;
        }
        switch (typeof obj) {
            case "object":
            case "function":
                return getObjectHashCode(obj);
            case "number":
                return numberHashCode(obj);
            case "boolean":
                return obj ? 1231 : 1237;
            default:
                return getStringHashCode(String(obj)); 
        }
    }
    })(),
    'kotlin.wasm.internal.isNullish' : (ref) => ref == null,
    'kotlin.wasm.internal.getJsTrue' : () => true,
    'kotlin.wasm.internal.getJsFalse' : () => false,
    'kotlin.wasm.internal.kotlinUIntToJsNumberUnsafe' : (x) => x >>> 0,
    'kotlin.wasm.internal.kotlinULongToJsBigIntUnsafe' : (x) => x & 0xFFFFFFFFFFFFFFFFn,
    'kotlin.wasm.internal.getCachedJsObject_$external_fun' : (p0, p1) => getCachedJsObject(p0, p1),
    'kotlin.wasm.internal.utoa32_$external_fun' : (p0) => String(p0),
    'kotlin.wasm.internal.utoa64_$external_fun' : (p0) => String(p0),
    'kotlin.wasm.internal.itoa32_$external_fun' : (p0) => String(p0),
    'kotlin.wasm.internal.itoa64_$external_fun' : (p0) => String(p0),
    'kotlin.js.stackPlaceHolder_js_code' : () => (''),
    'kotlin.js.message_$external_prop_getter' : (_this) => _this.message,
    'kotlin.js.name_$external_prop_setter' : (_this, v) => _this.name = v,
    'kotlin.js.stack_$external_prop_getter' : (_this) => _this.stack,
    'kotlin.js.kotlinException_$external_prop_getter' : (_this) => _this.kotlinException,
    'kotlin.js.kotlinException_$external_prop_setter' : (_this, v) => _this.kotlinException = v,
    'kotlin.js.JsError_$external_class_instanceof' : (x) => x instanceof Error,
    'kotlin.js.Promise_$external_fun' : (p0) => new Promise(p0),
    'kotlin.js.__callJsClosure_((Js?)->Unit)' : (f, p0) => f(p0),
    'kotlin.js.__callJsClosure_((Js)->Unit)' : (f, p0) => f(p0),
    'kotlin.js.__convertKotlinClosureToJsClosure_2' : (f, trampoline) => getCachedJsObject(f, (p0, p1) => trampoline(f, p0, p1)),
    'kotlin.js.__convertKotlinClosureToJsClosure_1' : (f, trampoline) => getCachedJsObject(f, (p0) => trampoline(f, p0)),
    'kotlin.js.then_$external_fun' : (_this, p0, p1) => _this.then(p0, p1),
    'kotlin.random.initialSeed' : () => ((Math.random() * Math.pow(2, 32)) | 0),
    'kotlin.wasm.internal.getJsClassName' : (jsKlass) => jsKlass.name,
    'kotlin.wasm.internal.getConstructor' : (obj) => obj.constructor,
    'kotlinx.coroutines.tryGetProcess' : () => (typeof(process) !== 'undefined' && typeof(process.nextTick) === 'function') ? process : null,
    'kotlinx.coroutines.tryGetWindow' : () => (typeof(window) !== 'undefined' && window != null && typeof(window.addEventListener) === 'function') ? window : null,
    'kotlinx.coroutines.nextTick_$external_fun' : (_this, p0) => _this.nextTick(p0),
    'kotlinx.coroutines.__convertKotlinClosureToJsClosure_0' : (f, trampoline) => getCachedJsObject(f, () => trampoline(f, )),
    'kotlinx.coroutines.createScheduleMessagePoster' : (process) => () => Promise.resolve(0).then(process),
    'kotlinx.coroutines.__callJsClosure_(()->Unit)' : (f, ) => f(),
    'kotlinx.coroutines.createRescheduleMessagePoster' : (window) => () => window.postMessage('dispatchCoroutine', '*'),
    'kotlinx.coroutines.subscribeToWindowMessages' : (window, process) => {
        const handler = (event) => {
            if (event.source == window && event.data == 'dispatchCoroutine') {
                event.stopPropagation();
                process();
            }
        }
        window.addEventListener('message', handler, true);
    },
    'kotlinx.coroutines.setTimeout' : (window, handler, timeout) => window.setTimeout(handler, timeout),
    'kotlinx.coroutines.clearTimeout' : (handle) => { if (typeof clearTimeout !== 'undefined') clearTimeout(handle); },
    'kotlinx.coroutines.clearTimeout_$external_fun' : (_this, p0) => _this.clearTimeout(p0),
    'kotlinx.coroutines.setTimeout_$external_fun' : (p0, p1) => setTimeout(p0, p1),
    'kotlinx.coroutines.promiseSetDeferred' : (promise, deferred) => promise.deferred = deferred,
    'kotlinx.coroutines.internal.throwAsyncJsError' : (message, className, stack) => { 
            var error = new Error();
            error.message = message;
            error.name = className;
            error.stack = stack;
            if (typeof globalThis.reportError === 'function') {
                // Up-to-date browsers and some non-browser JS runtimes (Deno, Bun)
                globalThis.reportError(error);
            } else {
                // Fallback for old browsers (pre-2022), Node.js
                setTimeout(function () { throw error }, 0);
            }
         },
    'io.github.rajumark.kernelin.webgpu.newArray' : () => [],
    'io.github.rajumark.kernelin.webgpu.pushUniformEntry' : (entries, binding) => { entries.push({ binding, visibility: GPUShaderStage.COMPUTE, buffer: { type: 'uniform' } }); },
    'io.github.rajumark.kernelin.webgpu.pushStorageEntry' : (entries, binding, readOnly) => { entries.push({ binding, visibility: GPUShaderStage.COMPUTE, buffer: { type: readOnly ? 'read-only-storage' : 'storage' } }); },
    'io.github.rajumark.kernelin.webgpu.createBindGroupLayout' : (device, entries) => device.createBindGroupLayout({ entries }),
    'io.github.rajumark.kernelin.webgpu.createComputePipeline' : (device, module, layout, entryPoint) => device.createComputePipeline({ layout: device.createPipelineLayout({ bindGroupLayouts: [layout] }), compute: { module, entryPoint } }),
    'io.github.rajumark.kernelin.webgpu.createBuffer' : (device, size, usage) => device.createBuffer({ size, usage }),
    'io.github.rajumark.kernelin.webgpu.newDataView' : (bytes) => new DataView(new ArrayBuffer(bytes)),
    'io.github.rajumark.kernelin.webgpu.setUint32' : (view, offset, value) => { view.setUint32(offset, value >>> 0, true); },
    'io.github.rajumark.kernelin.webgpu.setFloat32' : (view, offset, value) => { view.setFloat32(offset, value, true); },
    'io.github.rajumark.kernelin.webgpu.setInt32' : (view, offset, value) => { view.setInt32(offset, value, true); },
    'io.github.rajumark.kernelin.webgpu.writeBuffer' : (device, buffer, data) => { device.queue.writeBuffer(buffer, 0, data); },
    'io.github.rajumark.kernelin.webgpu.dataViewBuffer' : (view) => view.buffer,
    'io.github.rajumark.kernelin.webgpu.now' : () => performance.now(),
    'io.github.rajumark.kernelin.webgpu.bufferSize' : (buffer) => buffer.size,
    'io.github.rajumark.kernelin.webgpu.pushBufferBinding' : (entries, binding, buffer) => { entries.push({ binding, resource: { buffer } }); },
    'io.github.rajumark.kernelin.webgpu.createBindGroup' : (device, layout, entries) => device.createBindGroup({ layout, entries }),
    'io.github.rajumark.kernelin.webgpu.maxWorkgroupsPerDimension' : (device) => device.limits.maxComputeWorkgroupsPerDimension,
    'io.github.rajumark.kernelin.webgpu.push' : (array, value) => { array.push(value); },
    'io.github.rajumark.kernelin.webgpu.submitDispatch' : (device, pipeline, group, gx, gy, from, to) => {
        const encoder = device.createCommandEncoder();
        const pass = encoder.beginComputePass();
        pass.setPipeline(pipeline);
        pass.setBindGroup(0, group);
        pass.dispatchWorkgroups(gx, gy);
        pass.end();
        for (let i = 0; i < from.length; i++) encoder.copyBufferToBuffer(from[i], 0, to[i], 0, from[i].size);
        device.queue.submit([encoder.finish()]);
    },
    'io.github.rajumark.kernelin.webgpu.readBack' : (staging, isInt) => staging.mapAsync(GPUMapMode.READ).then(() => {
        const copy = staging.getMappedRange().slice(0);
        staging.unmap();
        return isInt ? new Int32Array(copy) : new Float32Array(copy);
    }),
    'io.github.rajumark.kernelin.webgpu.destroyBuffer' : (buffer) => { buffer.destroy(); },
    'io.github.rajumark.kernelin.webgpu.gpuSupported' : () => typeof navigator !== 'undefined' && !!navigator.gpu,
    'io.github.rajumark.kernelin.webgpu.requestDevice' : () => (async () => {
        const adapter = await navigator.gpu.requestAdapter({ powerPreference: 'high-performance' });
        if (!adapter) throw new Error('No WebGPU adapter found');
        const device = await adapter.requestDevice({ requiredLimits: {
            maxStorageBufferBindingSize: adapter.limits.maxStorageBufferBindingSize,
            maxBufferSize: adapter.limits.maxBufferSize } });
        const info = adapter.info || {};
        device.kernelinDescription = [info.vendor, info.architecture].filter(Boolean).join(' ') || 'WebGPU';
        return device;
    })(),
    'io.github.rajumark.kernelin.webgpu.deviceDescription' : (device) => device.kernelinDescription,
    'io.github.rajumark.kernelin.webgpu.createShaderModule' : (device, code) => device.createShaderModule({ code }),
    'io.github.rajumark.kernelin.webgpu.compilationErrors' : (module) => module.getCompilationInfo().then(i => i.messages.filter(m => m.type === 'error').map(m => m.lineNum + ':' + m.message).join('; ')),
    'io.github.rajumark.kernelin.webgpu.typedArrayLength' : (array) => array.length,
    'io.github.rajumark.kernelin.webgpu.readFloat' : (array, index) => array[index],
    'risk.now' : () => performance.now()
}

const StringConstantsProxy = new Proxy({}, {
  get(_, prop) { return prop; }
});

export { wasmTag as __TAG };

export const importObject = {
    js_code,
    intrinsics: {
        memory: new WebAssembly.Memory({ initial: 0 }),
        tag: wasmTag
    },
    "'": StringConstantsProxy,
    'wasm:js-string': d2FzbTpqcy1zdHJpbmc,
};
    