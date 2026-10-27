# Forest Keyboard 0.9.13 — iPhone layout tuning

- Adjustable key height (44–62, default 54) in settings.
- Four-row letter layout by default; punctuation shortcut row can be enabled in settings.
- Symmetric half-key second-row offsets and wider return key.
- Visual key gaps sit inside full touch cells.
- Space key now has press feedback and cancellation handling; dragging back to the origin does not insert an accidental space.
- Existing input/candidate regression suite plus space-gesture and layout checks run in GitHub Actions.
- Real-device typing feel still requires testing on Huawei nova 5T.
- Debug builds install separately as 「Forest Keyboard 試用」 (`studio.forest.keyboard.preview`), preserving the existing app. Settings and learned/custom words are separate; enable and select the preview keyboard in Android settings.

Dictionary inspection found no exact duplicate candidates within entries. No dictionary words were removed.

---

# 🌲 Forest Keyboard

原生 Android 系統輸入法，繁體拼音優先。測試機：Huawei nova 5T。

## V0.9.12 · 個人詞庫與輸入設定

- 可新增／移除自訂短語，支援人名、品牌和固定譯名；拼音前綴會從背景建立的索引查詢。
- 自訂候選優先顯示，點選後以詞語取代 composing 拼音。
- 保留 V0.9.11 的自動學習、震動、按鍵音開關、清除詞頻與密碼欄位保護。

## V0.9.11 · 設定與學習資料控制

- 新增設定頁：自動學習、按鍵震動與按鍵音效可各自開關。
- 可清除已學習詞頻；保留鍵盤偏好與內建詞庫，並即時清空服務記憶體索引及候選快取。
- 密碼欄位隱藏候選、直接提交按鍵，不保留 composing 拼音；標記為不個人化學習的欄位不寫入詞頻。
- 數字鍵盤將空白的「更多」入口換成可直接輸入的 `RM`，使用馬來西亞令吉標記。

下一批候選工作：詞庫匯入／匯出、簡繁與臺／台偏好、錯詞刪除、標點全形／半形、鍵盤高度及主題調整、詞庫清理。模糊音和分詞評分待核心回歸測試擴充後再做。

## V0.9.10 · 拼音首字母候選

- 支援多音節拼音首字母查詢，例如 `jd` 可直接找到「覺得」。
- 首字母候選索引在背景預建，按鍵查詢使用索引，不逐鍵掃描整本詞庫。
- 完整拼音及既有前綴候選維持原排序路徑。

## V0.9.9 · 日常短語候選

- 補入依真實使用情境整理的繁體詞組：生日快樂、我覺得、手感還是不夠、這次好了很多、又下雨了、很好睡覺、繼續優化等。
- 完整拼音時走既有緊湊詞典索引，部分拼音由前綴索引預測，普通候選列與展開面板一致可選。
- 追加 15 條詞組，不改變候選查詢路徑與單鍵觸控反應。

### 保留 V0.9.8 即時打字觸感

- 觸覺與按鍵聲在手指按下時立即觸發，輸入動作不必等到放開。
- 加深按壓時的鍵帽色差，讓快速連點也看得到觸控回應。
- 使用 Android 系統按鍵聲與虛擬鍵觸覺回饋，跟隨裝置音效及觸覺設定。
- 保留 V0.9.6 的鍵距、49dp 鍵高、拼音候選和標點列。

### 保留 V0.9.7 按鍵回饋

- 點字母、標點和功能鍵時使用 Android 系統鍵盤輕觸回饋，尊重裝置的觸覺設定。
- 按住鍵帽會即時變深，放開恢復；不加延遲動畫，保留快速連打反應。
- 保留 V0.9.6 的鍵距、49dp 鍵高、拼音候選和標點列。

### 保留 V0.9.6 輕盈鍵距

- QWERTY 鍵帽視覺間距加大，透過縮窄繪製鍵帽保留原按鈕觸控區。
- 修正鍵盤邊距、鍵帽間距使用 dp，讓不同密度螢幕上的排列更一致。
- 鍵盤底板改為 iPhone 風格灰底與圓角；維持 49dp 高度和常用標點列。

### 保留 V0.9.5 個人候選召回

- 從展開面板選過的詞會加入個人索引，下次輸入相同拼音可回到普通候選列。
- 分段選字同時記錄當時完整拼音及已消耗前綴；完整詞仍優先。
- 個人索引啟動時在背景重建，查詢不掃全部學習記錄。
- 僅召回詞庫中與拼音相符的詞，舊硬拼組合不會因學習重新出現。
- 仍是本機詞頻學習，尚未支援自訂詞或自動中英混打。

### 保留 V0.9.4 候選品質

- 完整拼音匹配先於預測詞及分段單字；個人詞頻在匹配層級內排序。
- 分段選字的詞頻每次重新讀取，不再把學習順位固定在候選快取中。
- 展開只顯示詞庫詞與前綴候選，移除同音字硬拼的組合。
- 空白候選列改為可用的「」／、／：／；，移除尚未實作的繁簡及常用入口。

### 保留 V0.9.3 候選刷新與展開

- 繼續輸入或退格立即收起展開面板；只有按 ⌄ 才展開。
- 候選列及其按鈕重用，取消打字時的整列重建與透明度閃爍。
- 普通輸入與展開均使用詞庫／索引候選。
- 前綴桶由最多 24 提升為 96，展開候選最多 160，補回截斷候選。
- 這是候選可見性改善，詞库規模未大幅增加；缺字仍需個別回報。

### 保留 V0.9.2 分段選字

- 輸入 `buzhidao` 可整詞選「不知道」，或依次選「不」→「知」→「道」。
- 輸入 `geiwo` 可選「給我」，或選「給」後保留 `wo` 再選「我」。
- 選字消耗長度與候選一同在背景計算；不在點按時掃詞庫。
- 分段選字仍自動收起展開面板，剩餘拼音繼續顯示候選。

### 保留 V0.9.1 的輸入手感改善

- 詞庫、前綴索引預熱與候選生成在單一背景執行緒執行；快速按鍵合併查詢。
- 查詢結果檢查版本與 composing，過期結果不更新畫面。
- 手指按住候選時延後更新；舊拼音的候選不能替換新拼音。
- 加大候選點按範圍，保留展開／收起與個人詞頻。
- 主鍵盤固定標點列：中文 ，。？！；英文 , . ? !。
- 字母鍵帽微收窄，按鈕點按範圍與 49dp 高度保留。
- 修正 raw 提交重複與最後一個 composing 字母刪不乾淨。

標點在尚未選字時會保留原拼音一次，不自動猜字；要輸入中文句子，先選字再按標點。

## 驗證

GitHub Actions 執行 `gradle :app:testDebugUnitTest` 與 `gradle :app:assembleDebug`。
Robolectric 回歸測試涵蓋 composing、快速查詢、過期候選、前綴與展開一致性。
Artifact：`Forest-Keyboard-V0.9.13-debug`。

真機重點：快速輸入／退格、橫向及展開選字、空格選字、中文／英文標點、切 App。
本版仍待真機 QA，不代表手感已驗收。

