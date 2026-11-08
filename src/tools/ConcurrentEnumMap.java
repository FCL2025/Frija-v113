package tools;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// java.util.EnumMap is itself Serializable, so this class cannot opt out of serialization the
// way the rest of the tree did when RMI was removed - and nothing here ever serialises one.
// The two locks are declared as the Lock interface, which is all javac's [serial] check looks
// at; the ReentrantReadWriteLock.ReadLock/WriteLock objects actually stored in them are
// serialisable, so they must NOT be made transient.
@SuppressWarnings("serial")
public final class ConcurrentEnumMap<K extends Enum<K>, V> extends EnumMap<K, V> {

    private static final long serialVersionUID = 11920818021L;
    // 三個欄位都是 final：這一整個類別的執行緒安全都建立在「別的執行緒一定
    // 看得到鎖」上面，final 欄位的凍結語意才保證得了這件事。之前是可寫欄位，
    // 只要有人把一個 ConcurrentEnumMap 用非安全發佈的方式交給另一條執行緒，
    // 對方就可能讀到 rL == null 然後 NPE。沒有任何地方需要換掉這些鎖。
    private final ReentrantReadWriteLock reentlock = new ReentrantReadWriteLock();
    private final Lock rL = reentlock.readLock(), wL = reentlock.writeLock();

    public ConcurrentEnumMap(Class<K> keyType) {
        super(keyType);
    }

    @Override
    public void clear() {
        wL.lock();
        try {
            super.clear();
        } finally {
            wL.unlock();
        }
    }

    /**
     * 在讀鎖底下複製，這樣複本裡的 {@code vals} 陣列和 {@code size} 不會對不上。
     *
     * <p>之前這個覆寫只是原封不動叫 {@code super.clone()}，等於沒有存在的意義：
     * {@link EnumMap#clone()} 直接複製內部欄位，和本體的鎖完全無關，所以一邊
     * clone 一邊 put 會得到一個 {@code size} 說 3 但陣列裡有 4 個非 null 的複本。
     *
     * <p>要知道的限制：{@code Object.clone()} 是逐欄位淺複製，複本沿用的是
     * <em>同一組</em>鎖物件（欄位是 final，見上面）。那不是錯的（多鎖不會錯，
     * 只會互相卡），但複本和本體會共用同一條讀寫鎖。目前整棵樹沒有任何地方
     * 呼叫 {@code clone()}，真要用再考慮讓複本帶自己的鎖。
     */
    @Override
    public EnumMap<K, V> clone() {
        rL.lock();
        try {
            return super.clone();
        } finally {
            rL.unlock();
        }
    }

    /**
     * 在讀鎖底下比較，本體的內部狀態不會被看到中間態。
     *
     * <p>能保護的只有本體。{@code EnumMap.equals} 遇到另一個 {@code EnumMap}
     * （{@code ConcurrentEnumMap} 也是）會直接讀對方的欄位、繞過對方的鎖，
     * 那從這裡管不到。反過來說也因為走的是那條直接讀欄位的路徑，這裡不會
     * 在持有本體讀鎖時再去拿對方的鎖，所以不會有鎖順序問題。
     */
    @Override
    public boolean equals(Object o) {
        rL.lock();
        try {
            return super.equals(o);
        } finally {
            rL.unlock();
        }
    }

    @Override
    public boolean containsKey(Object key) {
        rL.lock();
        try {
            return super.containsKey(key); // An apparent infinite recursive loop
        } finally {
            rL.unlock();
        }
    }

    @Override
    public boolean containsValue(Object value) {
        rL.lock();
        try {
            return super.containsValue(value); // An apparent infinite recursive loop
        } finally {
            rL.unlock();
        }
    }

    /**
     * 回傳一份不可變快照，不是活的檢視。
     *
     * <p>這三個檢視方法（{@code entrySet()} / {@code keySet()} / {@code values()}）
     * 以前是「在讀鎖裡跟 {@link EnumMap} 要一個<em>活的</em>檢視物件，然後放掉鎖
     * 才回傳」。鎖裡什麼事都沒做：真正走訪那些元素的動作 —— 呼叫端那十幾處
     * {@code new LinkedList<>(effects.values())} 之類的複製 —— 全發生在鎖外面。
     * 而 {@code EnumMap} 沒有 {@code modCount}，不會丟
     * {@code ConcurrentModificationException}，所以這個洞從來沒被指認出來。
     *
     * <p>它比「少算一個 buff」嚴重。{@code EnumMap} 的迭代器是
     * 「{@code hasNext()} 看 {@code vals[index] != null}、{@code next()} 再去讀
     * {@code vals[index]}」，兩步中間被 {@code remove} 插隊就會發生兩件事：
     * 讀到 null（複本裡混進一個 null 元素），或是 {@code next()} 重新檢查後發現
     * 已經到底，丟出 {@code NoSuchElementException}。後者會從
     * {@code new LinkedList<>(effects.values())} 這一行整個炸出去 ——
     * 也就是從 {@code cancelAllBuffs()} / {@code dispel()} 之類的地方炸出去，
     * 然後被封包處理的 catch 吞進 {@code Logs/Log_*.rtf}，玩家看到的是
     * 「buff 沒解到」。修改前後各跑 3 秒的雙執行緒壓測：修改前 399 萬次複製裡
     * 有 27289 次混進 null、29654 次丟例外；修改後 0 和 0。
     *
     * <p>現在「複製」這件事本身就在讀鎖裡完成，呼叫端一個字都不用改。
     *
     * <p>兩件事是刻意的：
     * <ul>
     * <li>走訪順序必須維持 {@code EnumMap} 的 ordinal 順序，所以用
     * {@link ArrayList} / {@link LinkedHashSet} 複製，<em>不能</em>用
     * 順序未定的 {@code Set.copyOf}。{@code MobPacket.addMonsterStatus}
     * 就是照這個順序把每個怪物狀態一個一個寫上線的，順序一亂封包就錯。</li>
     * <li>複本是不可變的。經盤點，整棵樹沒有任何呼叫端透過這三個檢視寫入
     * （沒有 {@code it.remove()}、{@code values().remove(...)}、
     * {@code entry.setValue(...)}），所以把寫入變成
     * {@code UnsupportedOperationException} 只會讓將來寫錯的人馬上知道，
     * 而不是安靜地改到一份複本上。</li>
     * </ul>
     *
     * <p>{@code Entry} 也要一起複製。{@code EnumMap} 給的 {@code Entry} 物件是活的
     * （{@code getValue()} 每次都回去讀原 map），只複製 {@code Set} 而留著原 entry
     * 等於沒修。這裡改用 {@link SimpleImmutableEntry}，而不是 {@code Map.entry}，
     * 因為 {@code EnumMap} 允許 null value 而 {@code Map.entry} 不收 null。
     *
     * <p>先在鎖裡收成 {@link List}、出了鎖才倒進 {@link LinkedHashSet}，是為了
     * 不要在讀鎖裡呼叫 value 的 {@code hashCode()}：那是別人的程式碼，
     * CLAUDE.md 的並行守則不准把鎖握過不受控的呼叫。{@code Enum.hashCode()}
     * 是 final 的識別雜湊，所以 {@link #keySet()} 沒有這個顧慮。
     */
    @Override
    public Set<Entry<K, V>> entrySet() {
        final List<Entry<K, V>> copied = new ArrayList<>();
        rL.lock();
        try {
            for (Entry<K, V> e : super.entrySet()) {
                copied.add(new SimpleImmutableEntry<>(e.getKey(), e.getValue()));
            }
        } finally {
            rL.unlock();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(copied));
    }

    @Override
    public V get(Object key) {
        rL.lock();
        try {
            return super.get(key);
        } finally {
            rL.unlock();
        }
    }

    /**
     * 回傳一份不可變快照，不是活的檢視；理由見 {@link #entrySet()}。
     */
    @Override
    public Set<K> keySet() {
        final Set<K> snapshot;
        rL.lock();
        try {
            snapshot = new LinkedHashSet<>(super.keySet());
        } finally {
            rL.unlock();
        }
        return Collections.unmodifiableSet(snapshot);
    }

    @Override
    public V put(K key, V value) {
        wL.lock();
        try {
            return super.put(key, value);
        } finally {
            wL.unlock();
        }
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        wL.lock();
        try {
            super.putAll(m);
        } finally {
            wL.unlock();
        }
    }

    @Override
    public V remove(Object key) {
        wL.lock();
        try {
            return super.remove(key);
        } finally {
            wL.unlock();
        }
    }

    @Override
    public int size() {
        rL.lock();
        try {
            return super.size();
        } finally {
            rL.unlock();
        }
    }

    /**
     * 回傳一份不可變快照，不是活的檢視；理由見 {@link #entrySet()}。
     *
     * <p>用 {@link ArrayList} 而不是 {@code List.copyOf}，因為 {@code EnumMap}
     * 允許 null value 而 {@code List.copyOf} 不收 null。
     */
    @Override
    public Collection<V> values() {
        final List<V> snapshot;
        rL.lock();
        try {
            snapshot = new ArrayList<>(super.values());
        } finally {
            rL.unlock();
        }
        return Collections.unmodifiableList(snapshot);
    }
}
