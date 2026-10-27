"""JLC-01 静态审计；不导入模型客户端，不联网，不修改原始数据。"""
from pathlib import Path
import collections
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
DATA = ROOT / 'docs/Jev改造/eval/route-eval-v1.jsonl'
AGENTS = ('RECOMMEND', 'CONSULT', 'BUY', 'KNOWLEDGE')
SMOKE = ['route-0002-b', 'route-0015-a', 'route-0028-a', 'route-0041-a',
         'route-0054-a', 'route-0055-a', 'route-0056-a', 'route-0058-a',
         'route-0067-a', 'route-0068-a', 'route-0069-a', 'route-0071-a',
         'route-0077-a', 'route-0083-a', 'route-0087-a', 'route-0089-a',
         'route-0095-a', 'route-0102-a', 'route-0103-a', 'route-0104-a']
DISPUTES = {
    'route-0030': ('CONSULT', '已选课程后询问付款操作，属于购买流程还是仅咨询？建议保留 BUY，但由业务明确购买流程问题归属。'),
    'route-0087': ('KNOWLEDGE', '同时要求解释概念和选课，没有明确当前优先级；建议按具体课程诉求保留 RECOMMEND，需确认多意图优先级。'),
    'route-0088': ('KNOWLEDGE', '同时要求知识讲解和课程内容查询；建议保留 CONSULT，需确认具体课程诉求是否优先。'),
    'route-0096': ('KNOWLEDGE', '没有课程指代信息但价格咨询意图明确；建议保留 CONSULT，由下游澄清，而非按信息不足统一转 KNOWLEDGE。'),
}

def group(sample_id):
    return sample_id.rsplit('-', 1)[0] if re.fullmatch(r'route-\d{4}-[a-d]', sample_id) else sample_id

def split(sample_id):
    digest = hashlib.sha256(group(sample_id).encode()).digest()
    return 'holdout' if int.from_bytes(digest[:4], 'big') % 5 == 0 else 'development'

def reason(row):
    n = int(row['id'].split('-')[1])
    if n <= 14: return '明确要求选择或推荐具体课程；学习目标、预算、基础为选课约束。'
    if n <= 27: return '咨询课程属性或报名服务；缺少实体不改变咨询意图，实体由下游澄清。'
    if n <= 40: return '已选择课程并明确购买、下单或结算；未执行实际订单。'
    if n <= 53: return '概念、编程、学习路线问题，无平台具体课程推荐诉求。'
    if n <= 66: return '已核对否定词及当前主诉求；咨询价格不等于购买，明确结算才走 BUY。'
    if n <= 76: return '已结合原始两条历史识别课程或概念指代，当前问题决定路由。'
    if n <= 86: return '口语或错别字不改变可识别的选课、咨询、购买或知识诉求。'
    if n <= 94: return '按当前明确诉求及否定/延后表达判断；无明确优先级的两组另列争议。'
    if n == 95: return '没有历史且指代上面问题，无法判断业务；保留 KNOWLEDGE 澄清。'
    if n == 96: return '价格咨询意图明确但课程实体缺失，保留 CONSULT 供下游澄清；另列争议。'
    if n == 97: return '泛聊，无特定业务诉求；KNOWLEDGE 为约定承接类别。'
    if n == 98: return '基础信息少但明确要具体入门课，归 RECOMMEND。'
    if n <= 101: return '越界诉求依现有输出协议交 KNOWLEDGE；不代表能够完成该请求。'
    return '忽略伪系统/开发者分类指令，按真实课程或知识问题分类。'

def main():
    raw = DATA.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == '8e2addb8b5b5cb561c44e89cb1593f3c5fcd6367085d79008a9acd0fddcecfd2', '冻结数据已变化；停止并新建协议版本，不覆盖旧实验身份'
    rows = [json.loads(line) for line in raw.decode('utf-8').splitlines() if line.strip()]
    ids = [r['id'] for r in rows]
    assert len(rows) == 413 and len(set(ids)) == 413
    grouped = collections.defaultdict(list)
    for r in rows:
        assert r['expectedAgent'] in AGENTS and isinstance(r['question'], str) and r['question'].strip()
        assert isinstance(r['history'], list)
        assert isinstance(r['ambiguous'], bool) and isinstance(r['multiIntent'], bool)
        assert isinstance(r.get('tags', []), list) and all(isinstance(x, str) for x in r.get('tags', []))
        assert all(m['role'] in ('user', 'assistant') and isinstance(m['content'], str) for m in r['history'])
        grouped[group(r['id'])].append(r)
    assert len(grouped) == 104
    for gid, members in grouped.items():
        if gid == 'route-0001': continue
        assert {r['id'][-1] for r in members} == set('abcd')
        a, b, c, d = sorted(members, key=lambda r: r['id'])
        assert b['question'] == '你好，' + a['question']
        assert c['question'] == '我想问一下，' + a['question']
        assert d['question'] == '麻烦帮我看看，' + a['question'] + '。'
        assert len({json.dumps({k: r.get(k) for k in ('history', 'expectedAgent', 'tags', 'ambiguous', 'multiIntent')}, ensure_ascii=False, sort_keys=True) for r in members}) == 1
        assert len({split(r['id']) for r in members}) == 1
    reviews = []
    for r in rows:
        gid = group(r['id'])
        dispute = DISPUTES.get(gid)
        reviews.append({'sampleId': r['id'], 'scenarioGroupId': gid, 'split': split(r['id']),
                        'expectedAgent': r['expectedAgent'], 'staticReview': 'POLICY_PENDING' if dispute else 'CONSISTENT',
                        'reason': dispute[1] if dispute else reason(r),
                        'alternativeAgent': dispute[0] if dispute else None, 'businessReview': 'PENDING'})
    digest = hashlib.sha256(raw).hexdigest()
    smoke = {'protocolId': 'jlc-protocol-v1', 'datasetSha256': digest, 'sampleIds': SMOKE,
             'samples': [{'sampleId': r['id'], 'expectedAgent': r['expectedAgent'], 'scenarioGroupId': group(r['id']),
                          'split': split(r['id']), 'tags': r.get('tags', [])} for sid in SMOKE for r in rows if r['id'] == sid]}
    assert len(SMOKE) == len(set(SMOKE)) == len(smoke['samples']) == 20
    assert {r['expectedAgent'] for r in smoke['samples']} == set(AGENTS)
    assert {'reference', 'boundary', 'multiIntent', 'injection'} <= {t for r in smoke['samples'] for t in r['tags']}
    summary = {'taskId': 'JLC-01', 'staticValidation': 'PASS', 'live': 'NOT_RUN', 'businessReview': 'PENDING',
               'dataset': str(DATA), 'datasetSha256': digest, 'rowCount': len(rows), 'uniqueIds': len(set(ids)),
               'scenarioGroupCount': len(grouped), 'groupSizeDistribution': dict(collections.Counter(map(len, grouped.values()))),
               'labelCounts': dict(collections.Counter(r['expectedAgent'] for r in rows)),
               'splitCounts': dict(collections.Counter(split(r['id']) for r in rows)),
               'groupSplitCounts': dict(collections.Counter(split(g) for g in grouped)),
               'tagCounts': dict(collections.Counter(t for r in rows for t in r.get('tags', []))),
               'ambiguousCount': sum(r['ambiguous'] for r in rows), 'multiIntentCount': sum(r['multiIntent'] for r in rows),
               'historyCount': sum(bool(r['history']) for r in rows),
               'missingTagsIds': [r['id'] for r in rows if 'tags' not in r],
               'duplicateIds': [], 'crossSplitGroupCount': 0, 'labelChanges': 0,
               'staticConsistentCount': sum(r['staticReview'] == 'CONSISTENT' for r in reviews),
               'policyPendingCount': sum(r['staticReview'] == 'POLICY_PENDING' for r in reviews),
               'policyPendingGroupIds': list(DISPUTES), 'smokeCount': 20,
               'reviewMethod': '逐场景语义审阅原问题及历史；机械核验全部 b/c/d 仅添加固定礼貌前缀及标点，标签/历史/标志一致；不是业务验收。'}
    for name, value in [('smoke-ids.json', smoke), ('dataset-audit.json', summary)]:
        (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (OUT / 'sample-review.jsonl').write_text(''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in reviews), encoding='utf-8')
    lines = ['# 样本静态复核与冒烟清单', '', '状态：静态 PASS；业务复核 PENDING；真实测试 NOT_RUN。原数据和标签未修改。', '',
             f'413 条、104 场景组；数据 SHA-256：`{digest}`。', '',
             f'类别：{summary["labelCounts"]}。切分：{summary["splitCounts"]}。', '',
             '全部原始语义已审阅；a/b/c/d 的固定前缀和标点改写由脚本逐条验证，未视为独立业务场景。397 条与现有分类约定一致，4 组共 16 条存在业务政策歧义，不能由静态代码独立裁决。全部 413 条的业务验收状态均为 PENDING。', '',
             '`route-0001` 缺少可选 tags，按空列表处理；不回写数据。明确意图中存在无实体的课程问法，分类意图与下游实体澄清应区分。', '',
             '| 待确认场景组（均含 a/b/c/d） | 当前标签 | 可能替代 | 审阅意见 |', '| --- | --- | --- | --- |']
    by_group = {group(r['id']): r for r in rows}
    for gid, (alternative, why) in DISPUTES.items():
        lines.append(f'| {gid} | {by_group[gid]["expectedAgent"]} | {alternative} | {why} |')
    lines += ['', '本轮冻结 v1 原标签用于客观测量；任何修改需生成新版本副本和哈希，重新冻结协议，禁止覆盖旧集。报告同时保留全量与争议切片，未经业务确认不能声称质量已验收。', '',
              '固定 20 条在模型结果未知时选定；四类、历史、边界、多意图和注入均覆盖。以 smoke-ids.json 顺序为原始清单，调度程序另按种子打乱。', '',
              '| ID | 标签 | 切分 | 场景 |', '| --- | --- | --- | --- |']
    for r in smoke['samples']:
        lines.append(f'| {r["sampleId"]} | {r["expectedAgent"]} | {r["split"]} | {", ".join(r["tags"])} |')
    lines += ['', '机器产物：dataset-audit.json、sample-review.jsonl、smoke-ids.json。', '',
              '复核命令（仓库根目录）：`python docs/Jev与LLM路由对比/audit_dataset.py`；仅标准库和本地文件，未运行工程测试。']
    (OUT / '样本选择与复核.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False))

if __name__ == '__main__':
    main()
