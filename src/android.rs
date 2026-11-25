use anyhow::{anyhow, Result};
use jni::objects::{JObject, JObjectArray, JString, JValue};
use jni::JavaVM;
use std::sync::OnceLock;

static PTRS: OnceLock<(usize, usize)> = OnceLock::new();

pub fn init(app: &slint::android::AndroidApp) {
    let _ = PTRS.set((app.vm_as_ptr() as usize, app.activity_as_ptr() as usize));
}

/// همه فراخوانی‌های JNI روی نخ اصلی (UI) انجام می‌شوند.
fn with_env<T>(f: impl FnOnce(&mut jni::JNIEnv, &JObject) -> Result<T>) -> Result<T> {
    let (vm_p, act_p) = *PTRS.get().ok_or_else(|| anyhow!("android not initialised"))?;
    let vm = unsafe { JavaVM::from_raw(vm_p as *mut jni::sys::JavaVM) }?;
    let mut guard = vm.attach_current_thread()?;
    let act = unsafe { JObject::from_raw(act_p as jni::sys::jobject) };
    let r = f(&mut guard, &act);
    // activity متعلق به Android است؛ نباید local ref آن آزاد شود
    std::mem::forget(act);
    r
}

pub fn start_pick() -> Result<()> {
    with_env(|env, act| {
        let cls = env.get_object_class(act)?;
        env.call_static_method(cls, "startPick", "(Landroid/app/Activity;)V", &[JValue::Object(act)])?;
        Ok(())
    })
}

/// None یعنی هنوز انتخابی تمام نشده؛ Some(مسیرها) یعنی انتخاب تمام شده است.
pub fn take_picked() -> Result<Option<Vec<String>>> {
    with_env(|env, act| {
        let cls = env.get_object_class(act)?;
        let obj = env
            .call_static_method(cls, "takePicked", "()[Ljava/lang/String;", &[])?
            .l()?;
        if obj.is_null() {
            return Ok(None);
        }
        let arr = JObjectArray::from(obj);
        let n = env.get_array_length(&arr)?;
        let mut out = Vec::new();
        for i in 0..n {
            let s = env.get_object_array_element(&arr, i)?;
            let js = JString::from(s);
            let text: String = env.get_string(&js)?.into();
            out.push(text);
        }
        Ok(Some(out))
    })
}

pub fn native_lib_dir() -> Result<String> {
    with_env(|env, act| {
        let cls = env.get_object_class(act)?;
        let obj = env
            .call_static_method(cls, "nativeLibDir", "(Landroid/app/Activity;)Ljava/lang/String;", &[JValue::Object(act)])?
            .l()?;
        let js = JString::from(obj);
        let text: String = env.get_string(&js)?.into();
        Ok(text)
    })
}
