package android.content;
// 手写等价物（本机无法运行 x86 aidl）。oneway，descriptor 与事务码同 aidl 生成结果。
public interface IOnPrimaryClipChangedListener extends android.os.IInterface {
    String DESCRIPTOR = "android.content.IOnPrimaryClipChangedListener";
    void dispatchPrimaryClipChanged() throws android.os.RemoteException;
    abstract class Stub extends android.os.Binder implements IOnPrimaryClipChangedListener {
        static final int TRANSACTION_dispatchPrimaryClipChanged = android.os.IBinder.FIRST_CALL_TRANSACTION;
        public Stub() { attachInterface(this, DESCRIPTOR); }
        @Override public android.os.IBinder asBinder() { return this; }
        @Override protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
            if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) data.enforceInterface(DESCRIPTOR);
            if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
            if (code == TRANSACTION_dispatchPrimaryClipChanged) { data.enforceNoDataAvail(); dispatchPrimaryClipChanged(); return true; }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
