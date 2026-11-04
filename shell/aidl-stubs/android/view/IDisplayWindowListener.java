package android.view;
// 手写等价物。oneway；事务码顺序与 .aidl 声明顺序一致。
public interface IDisplayWindowListener extends android.os.IInterface {
    String DESCRIPTOR = "android.view.IDisplayWindowListener";
    void onDisplayAdded(int displayId) throws android.os.RemoteException;
    void onDisplayConfigurationChanged(int displayId, android.content.res.Configuration newConfig) throws android.os.RemoteException;
    void onDisplayRemoved(int displayId) throws android.os.RemoteException;
    abstract class Stub extends android.os.Binder implements IDisplayWindowListener {
        static final int TRANSACTION_onDisplayAdded = android.os.IBinder.FIRST_CALL_TRANSACTION;
        static final int TRANSACTION_onDisplayConfigurationChanged = android.os.IBinder.FIRST_CALL_TRANSACTION + 1;
        static final int TRANSACTION_onDisplayRemoved = android.os.IBinder.FIRST_CALL_TRANSACTION + 2;
        public Stub() { attachInterface(this, DESCRIPTOR); }
        @Override public android.os.IBinder asBinder() { return this; }
        @Override protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
            if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) data.enforceInterface(DESCRIPTOR);
            if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
            switch (code) {
                case TRANSACTION_onDisplayAdded: { int a = data.readInt(); onDisplayAdded(a); return true; }
                case TRANSACTION_onDisplayConfigurationChanged: {
                    int a = data.readInt();
                    android.content.res.Configuration c = data.readInt() != 0 ? android.content.res.Configuration.CREATOR.createFromParcel(data) : null;
                    onDisplayConfigurationChanged(a, c); return true; }
                case TRANSACTION_onDisplayRemoved: { int a = data.readInt(); onDisplayRemoved(a); return true; }
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
