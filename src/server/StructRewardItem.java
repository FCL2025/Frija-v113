package server;

/**
 * 開啟型道具的獎勵項目（由 {@code Reward.img} 讀進來）。D6 改成 record：
 * 原本是「new 一個空殼再逐欄位指派」的可變結構，欄位在填完之後就不再改。
 */
public record StructRewardItem(int itemid, long period, short prob, short quantity,
        String effect, String worldmsg) {
}
