package util;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BuyUtil implements PurchasesUpdatedListener, BillingClientStateListener {
    private static final long RECONNECT_TIMER_START_MILLISECONDS = 1L * 1000L;
    private static final long RECONNECT_TIMER_MAX_TIME_MILLISECONDS = 1000L * 60L * 15L; // 15 mins
    private static final Handler handler = new Handler(Looper.getMainLooper());
    protected Handler uiHandler = new Handler(Looper.getMainLooper());
    private BillingClient billingClient;
    private boolean billingSetupComplete = false;
    private JSONObject products;
    private String[] cacheRequestList;
    private String buyProductId;
    private Map<String, SkuDetails> skuDetailsLiveDataMap=new HashMap<>();
    private long reconnectMilliseconds = RECONNECT_TIMER_START_MILLISECONDS;
    BuyLister mBuyLister;
    public interface BuyLister{
        void getList(List<SkuDetails> skuDetails);
        void onComplete(boolean isRepeatBuy);
        void onError(String message);
    }

    public BuyUtil(JSONObject products){
        this.products = products;
    }

    public void setOnBuyLister(BuyLister mBuyLister){
        this.mBuyLister = mBuyLister;
    }

    public Activity getAct(){
        return null;
    }

    public void init(){
        billingClient = BillingClient.newBuilder(getAct()).setListener(this).enablePendingPurchases().build();
        billingClient.startConnection(this);
    }
    @Override
    public void onBillingServiceDisconnected() {
        billingSetupComplete = false;
        retryBillingServiceConnectionWithExponentialBackoff();
    }

    @Override
    public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
        int responseCode = billingResult.getResponseCode();
        String debugMessage = billingResult.getDebugMessage();
        switch (responseCode) {
            case BillingClient.BillingResponseCode.OK:
                // The billing client is ready. You can query purchases here.
                // This doesn't mean that your app is set up correctly in the console -- it just
                // means that you have a connection to the Billing service.
                reconnectMilliseconds = RECONNECT_TIMER_START_MILLISECONDS;
                billingSetupComplete = true;
                RequstProduct(products);
                break;
            case BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE:
            case BillingClient.BillingResponseCode.BILLING_UNAVAILABLE:
                PrintLog("Google商品伺服器連線異常"+debugMessage);
                break;
            default:
                retryBillingServiceConnectionWithExponentialBackoff();
                break;
        }
    }

    public void RequstProduct(JSONObject jObject){
        uiHandler.post(new Runnable() {

            @Override
            public void run() {
                String[] realProducts=null;
                try {
                    JSONArray jArray=jObject.getJSONArray("productIds");
                    realProducts=new String[jArray.length()];
                    for (int i = 0; i < jArray.length(); i++) {
                        realProducts[i]=jArray.getString(i);
                    }
                } catch (Exception e) {
                    PrintLog("資料解析錯誤："+e.getMessage());
                }
                if(realProducts!=null){
                    OnRequstProduct(realProducts);
                }else{
                    PrintLog("資料解析錯誤");
                }
            }
        });
    }

    protected void OnRequstProduct(String[] productId) {
        List<String> skuList = new ArrayList<>();
        skuList.addAll(Arrays.asList(productId));
        cacheRequestList=productId;
        SkuDetailsParams.Builder params = SkuDetailsParams.newBuilder();
        params.setSkusList(skuList).setType(BillingClient.SkuType.SUBS);
        billingClient.querySkuDetailsAsync(params.build(),
                new SkuDetailsResponseListener() {
                    @Override
                    public void onSkuDetailsResponse(BillingResult billingResult,
                                                     List<SkuDetails> skuDetailsList) {
                        int responseCode = billingResult.getResponseCode();

                        switch (responseCode){
                            case BillingClient.BillingResponseCode.OK:
                                RecieveProducts(skuDetailsList);
                                break;
                            default:
                                PrintLog("查詢商品失敗: "+billingResult.getDebugMessage());
                                break;
                        }
                    }
                });
    }

    private void RecieveProducts(List<SkuDetails> skuDetailsList){
        ArrayList<SkuItem> skuItems=new ArrayList<SkuItem>();
        ArrayList<String> invaildIds=new ArrayList<String>();
        int length=cacheRequestList.length;
        if(cacheRequestList!=null && length>0){
            for(int i=0;i<length;i++){
                String productId=cacheRequestList[i];
                if(!TextUtils.isEmpty(productId)){
                    SkuDetails detail=null;
                    for (SkuDetails skuDetails : skuDetailsList) {
                        if(skuDetails.getSku().equals(productId)){
                            detail=skuDetails;
                            break;
                        }
                    }

                    if(detail==null){
                        PrintLog("未找到該商品資料:"+productId);
                        invaildIds.add(productId);
                        continue;
                    }
                    skuDetailsLiveDataMap.put(productId,detail);

                    String price=detail.getPrice();
                    String formatPrice=price;

                    SkuItem skuItem=new SkuItem();
                    skuItem.productId=productId;
                    skuItem.title=detail.getTitle();
                    skuItem.desc=detail.getDescription();
                    skuItem.price=price;
                    skuItem.formatPrice=formatPrice;
                    skuItem.priceCurrencyCode=detail.getPriceCurrencyCode();
                    skuItem.skuType=detail.getType();

                    skuItems.add(skuItem);
                }
            }
        }
        uiHandler.post(new Runnable() {

            @Override
            public void run() {
                mBuyLister.getList(skuDetailsList);
            }
        });
    }

    public void BuyProduct(final String pid){
        uiHandler.post(new Runnable() {

            @Override
            public void run() {
                OnBuyProduct(pid);
            }
        });
    }

    protected void OnBuyProduct(String productId) {
        SkuDetails skuDetails=skuDetailsLiveDataMap.get(productId);
        if(null!=skuDetails){
            buyProductId=productId;

            BillingFlowParams purchaseParams =
                    BillingFlowParams.newBuilder()
                            .setSkuDetails(skuDetails)
                            .build();

            billingClient.launchBillingFlow(getAct(), purchaseParams);
        }else{
            PrintLog("未找到該商品資料:"+productId);
        }
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult billingResult, @Nullable List<Purchase> list) {
        int responseCode = billingResult.getResponseCode();

        switch (responseCode) {
            case BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED:
                mBuyLister.onComplete(true);
                break;
            case BillingClient.BillingResponseCode.OK:
                FlowFinish(true,null,list);
                break;
            case BillingClient.BillingResponseCode.USER_CANCELED:
                String productId=buyProductId;
                buyProductId=null;
                //PrintLog("商品購買取消："+productId);
                PrintLog("商品購買取消");
                break;
            default:
                FlowFinish(false,billingResult.getDebugMessage(),list);
                break;
        }
    }

    private void FlowFinish(Boolean isSuccess,String message,List<Purchase> purchases){
        if(isSuccess){
            if(buyProductId!=null){
                String productId=buyProductId;
                buyProductId=null;
                String purchaseToken=null;
                for (Purchase purchase : purchases) {
                    for (String skus : purchase.getSkus()) {
                        if(skus.contains(productId) &&
                                purchase.getPurchaseState()==Purchase.PurchaseState.PURCHASED){
                            purchaseToken=purchase.getPurchaseToken();
                            break;
                        }
                        if(purchaseToken!=null) break;
                    }
                }
                mBuyLister.onComplete(false);
            }
        }else{
            if(buyProductId!=null){
                PrintLog("購買商品失敗: " + message);
            }
        }
    }

    private void retryBillingServiceConnectionWithExponentialBackoff() {
        handler.postDelayed(() ->
                        billingClient.startConnection(this),
                reconnectMilliseconds);
        reconnectMilliseconds = Math.min(reconnectMilliseconds * 2,
                RECONNECT_TIMER_MAX_TIME_MILLISECONDS);
    }

    public void PrintLog(String message){
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                mBuyLister.onError(message);
            }
        });
    }

    private String GetResponseText(int responseCode){
        switch (responseCode){
            case BillingClient.BillingResponseCode.OK:
                return "OK";
            case BillingClient.BillingResponseCode.SERVICE_TIMEOUT:
                return "SERVICE_TIMEOUT";
            case BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED:
                return "FEATURE_NOT_SUPPORTED";
            case BillingClient.BillingResponseCode.USER_CANCELED:
                return "USER_CANCELED";
            case BillingClient.BillingResponseCode.SERVICE_DISCONNECTED:
                return "SERVICE_DISCONNECTED";
            case BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE:
                return "SERVICE_UNAVAILABLE";
            case BillingClient.BillingResponseCode.BILLING_UNAVAILABLE:
                return "BILLING_UNAVAILABLE";
            case BillingClient.BillingResponseCode.ITEM_UNAVAILABLE:
                return "ITEM_UNAVAILABLE";
            case BillingClient.BillingResponseCode.DEVELOPER_ERROR:
                return "DEVELOPER_ERROR";
            case BillingClient.BillingResponseCode.ERROR:
                return "ERROR";
            case BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED:
                return "ITEM_ALREADY_OWNED";
            case BillingClient.BillingResponseCode.ITEM_NOT_OWNED:
                return "ITEM_NOT_OWNED";
            default:
                return "UnKnown";
        }
    }

    public class SkuItem {
        public String productId;
        public String title;
        public String desc;
        public String price;
        public String formatPrice;//格式化价格，包括其货币符号
        public String priceCurrencyCode;//货币代码
        public String skuType;//内购还是订阅
    }
}
