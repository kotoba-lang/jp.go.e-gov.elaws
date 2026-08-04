# jp.go.e-gov.elaws

`laws.e-gov.go.jp` の **e-Gov 法令API v2** から取得した、**日本の現行法令の全文** を保全する DataLad dataset です。統合用 datom は `etzhayyim/global-legislation-datoms` が生成します。

**この dataset は「全文コーパス」です**——カタログや索引ではありません。憲法・法律・政令・勅令・府省令・規則の **9,536 件すべて**の本文を、API が返したバイト列そのままで保持します。

## レイヤ

| パス | 中身 | git での扱い |
|---|---|---|
| `raw/law-list.json` | `GET /api/2/laws` 全ページ（9,536 件のメタデータ） | git-annex → B2 |
| `raw/laws/<law_id>.json` | `GET /api/2/law_data/<law_id>`。`law_full_text` に条文全文 | git-annex → B2 |
| `raw/source-catalog.edn` | 全ファイルの sha256・バイト数（custody 記録） | **git 本体** |
| `index/laws.edn` | 法令 1 件 1 行。identity・題名・法令番号・公布日・施行日・廃止状態と、**本文の所在**（path + sha256 + bytes） | **git 本体** |
| `index/relations.edn` | 法令間の依存辺 | **git 本体** |

**`index/` を git 本体に置き、`raw/` だけを annex に送る**のが要点です。コーパスを *query* したいだけの消費者に 543 MB の `datalad get` を強いてはならない——索引は数 MB のテキストで git に載り、本文は sha256 で名指しして必要な時だけ取る。

## 現在地（2026-08-04 実測）

| | |
|---|---|
| 法令 | **9,536**（e-Gov が提供する全件。取得失敗 0 件） |
| うち全文取得済み | **9,536 / 9,536** |
| 全文バイト数 | 519,493,759 |
| 依存辺 | **7,793**（`:law.rel/amends`） |
| 種別内訳 | 府省令 4,426 / 政令 2,422 / 法律 2,142 / 規則 453 / 勅令 71 / 憲法 1 / 複合 21 |
| 効力 | 現行 8,979 / 廃止 414 / 失効 105 / 期限満了 38 |

## 依存辺の出どころ

e-Gov は各法令の **現行改正版がどの改正法によって作られたか**を `revision_info.amendment_law_id` として持っています。これは「改正法 → 被改正法」の辺そのもので、9,536 件中 7,793 件が持っています。**本文を自然言語解析して推定したものではありません**——上流が構造化データとして述べている事実だけを辺にしています。

wave-1 で取っていないもの（推測させないため明記する）:

- **条文本文中の法令引用**。日本の法令 XML は他法令を法令番号の文字列で参照し、URI 参照を持たないため、機械的な辺の抽出には別途の名寄せが要ります。
- **判例**。`courts.go.jp` は別ソースで、この dataset のスコープ外です。

## ライセンス

e-Gov 法令API の利用は **政府標準利用規約（第2.0版）**＝ CC BY 4.0 互換。出典を表示すれば複製・公衆送信・翻訳・変形が可能。`global-legislation-datoms` の格付けでは **Tier-A**。

## 再取得 / 再生成

```bash
nbb --classpath bin bin/fetch.cljs --pool 6   # raw/ を取得（既存ファイルは skip、中断しても再開可）
nbb --classpath bin bin/index.cljs            # index/ と raw/source-catalog.edn を再生成
```

`bin/fetch.cljs` は**受け取ったバイト列を一切加工せず**に保存します。`source-catalog.edn` の sha256 が e-Gov が実際に送ってきたものの sha256 であり、下流はこのスクリプトを信用せずに custody を検証できます。
