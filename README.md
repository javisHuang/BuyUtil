# BuyUtil
Google In-app Billing Class

使用方法：
JSONObject json = new JSONObject();
JSONArray jArray= new JSONArray(); //商品資料
jArray.put("product1");
jArray.put("product2");
jArray.put("product3");
jArray.put("product4");
jArray.put("product5");
try {
    json.put("productIds",jArray);
} catch (JSONException e) {
    e.printStackTrace();
}
BuyUtil buy = new BuyUtil(json){
    //覆寫並讓類取得actitivy
    @Override
    public Activity getAct(){
        return AppCompatBaseActivity.this;
    }
};
buy.setOnBuyLister(new BuyUtil.BuyLister() {
    //取得所有商品資料
    @Override
    public void getList(List<SkuDetails> skuDetails) {
        //自訂顯示在UI上的所有商品
        OrderAlert.getInstance(AppCompatBaseActivity.this).showOrder2Msg(skuDetails, pid -> {
            //選擇商品購買
            buy.BuyProduct(pid);
        });
    }
    
    //購買流程結束後，並回傳是否有重覆購買
    @Override
    public void onComplete(boolean isRepeatBuy) {
        String msg="";
        if(isRepeatBuy){
            msg="此商品已訂閱";
        }else{
            msg="訂閱成功";
        }
        Toast.makeText(AppCompatBaseActivity.this, msg, Toast.LENGTH_LONG).show();
    }

    //購買流程發生異常時
    @Override
    public void onError(String message) {
        Toast.makeText(AppCompatBaseActivity.this, message, Toast.LENGTH_LONG).show();
    }
});
//都準備好後執行
buy.init();
