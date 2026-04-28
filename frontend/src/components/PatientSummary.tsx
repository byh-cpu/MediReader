import { Button, Card, Collapse, Descriptions, Empty, Input, InputNumber, Select, Space, Table, Tag, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined, UserOutlined, BookOutlined, DashboardOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { BasicInfo, EvaluationMetrics, LabResult, SourceItem, StructuredInfo } from '../services/api';

interface PatientSummaryProps {
  patientSummary: string;
  structuredInfo?: StructuredInfo | null;
  sources: SourceItem[];
  evaluationMetrics?: EvaluationMetrics | null;
  editable?: boolean;
  onStructuredInfoChange?: (info: StructuredInfo) => void;
}

const GENDER_OPTIONS = ['男', '女', '未提供'];
const DEPARTMENT_OPTIONS = ['门急诊', '内科', '外科', '心内科', '呼吸内科', '消化内科', '内分泌科', '神经内科', '肾内科', '儿科', '妇科', '骨科', '未提供'];
const LAB_UNIT_OPTIONS = ['mmHg', 'mmol/L', 'mg/L', 'mg/dL', 'g/L', 'U/L', 'IU/L', '10^9/L', '10^12/L', '%', '次/分', '℃', 'kg', 'cm', 'mL/min', '阳性/阴性'];
const LAB_FLAG_OPTIONS = [
  { label: '正常', value: '' },
  { label: '升高 ↑', value: '↑' },
  { label: '降低 ↓', value: '↓' },
  { label: '阳性 +', value: '+' },
  { label: '阴性 -', value: '-' },
  { label: '异常', value: '异常' },
];

const emptyStructuredInfo = (): StructuredInfo => ({
  basicInfo: {},
  chiefComplaints: [],
  diagnoses: [],
  currentMedications: [],
  allergies: [],
  pastMedicalHistory: [],
  labResults: [],
  riskFactors: [],
  uncertainties: [],
  evidence: [],
});

const normalizeInfo = (info?: StructuredInfo | null): StructuredInfo => ({
  ...emptyStructuredInfo(),
  ...(info ?? {}),
  basicInfo: { ...(info?.basicInfo ?? {}) },
  chiefComplaints: info?.chiefComplaints ?? [],
  diagnoses: info?.diagnoses ?? [],
  currentMedications: info?.currentMedications ?? [],
  allergies: info?.allergies ?? [],
  pastMedicalHistory: info?.pastMedicalHistory ?? [],
  labResults: info?.labResults ?? [],
  riskFactors: info?.riskFactors ?? [],
  uncertainties: info?.uncertainties ?? [],
  evidence: info?.evidence ?? [],
});

const numericText = (value?: string): string => {
  if (!value) return '';
  const matched = value.match(/\d+(\.\d+)?/);
  return matched?.[0] ?? '';
};

const renderTagList = (values?: string[], color = 'blue') => {
  if (!values || values.length === 0) return <span className="muted-text">未提供</span>;
  return (
    <Space wrap>
      {values.map((item, index) => (
        <Tag key={`${item}-${index}`} color={color}>
          {item}
        </Tag>
      ))}
    </Space>
  );
};

const PatientSummary: React.FC<PatientSummaryProps> = ({
  patientSummary,
  structuredInfo,
  sources,
  evaluationMetrics,
  editable = false,
  onStructuredInfoChange,
}) => {
  const info = normalizeInfo(structuredInfo);
  const basicInfo = info.basicInfo ?? {};
  const labResults = info.labResults ?? [];

  const updateInfo = (patch: Partial<StructuredInfo>) => {
    onStructuredInfoChange?.({ ...info, ...patch });
  };

  const updateBasicInfo = (key: keyof BasicInfo, value: string) => {
    updateInfo({ basicInfo: { ...basicInfo, [key]: value } });
  };

  const updateStringList = (key: keyof StructuredInfo, values: string[]) => {
    updateInfo({ [key]: values.filter(Boolean) } as Partial<StructuredInfo>);
  };

  const updateLab = (index: number, key: keyof LabResult, value: string) => {
    const nextLabs = labResults.map((item, i) => (i === index ? { ...item, [key]: value } : item));
    updateInfo({ labResults: nextLabs });
  };

  const addLab = () => {
    updateInfo({ labResults: [...labResults, { item: '', value: '', referenceRange: '', unit: '', flag: '' }] });
  };

  const removeLab = (index: number) => {
    updateInfo({ labResults: labResults.filter((_, i) => i !== index) });
  };

  const renderText = (value?: string, unit?: string) => {
    if (!value) return '未提供';
    return unit && !value.includes(unit) ? `${value} ${unit}` : value;
  };

  const renderInput = (key: keyof BasicInfo, placeholder = '未提供') => (
    <Input value={basicInfo[key] ?? ''} onChange={(e) => updateBasicInfo(key, e.target.value)} placeholder={placeholder} allowClear />
  );

  const renderNumberInput = (key: keyof BasicInfo, addonAfter: string, min = 0, max?: number) => (
    <InputNumber
      style={{ width: '100%' }}
      value={numericText(basicInfo[key]) ? Number(numericText(basicInfo[key])) : null}
      min={min}
      max={max}
      precision={addonAfter === 'kg' ? 1 : 0}
      addonAfter={addonAfter}
      placeholder="未提供"
      onChange={(value) => updateBasicInfo(key, value === null ? '' : String(value))}
    />
  );

  const renderSelect = (key: keyof BasicInfo, options: string[]) => (
    <Select
      showSearch
      allowClear
      style={{ width: '100%' }}
      value={basicInfo[key] || undefined}
      placeholder="请选择"
      options={options.map((item) => ({ label: item, value: item }))}
      onChange={(value) => updateBasicInfo(key, value ?? '')}
    />
  );

  const renderTagEditor = (key: keyof StructuredInfo, placeholder: string) => (
    <Select
      mode="tags"
      style={{ width: '100%' }}
      tokenSeparators={[',', '，', ';', '；', '\n']}
      value={(info[key] as string[] | undefined) ?? []}
      placeholder={placeholder}
      onChange={(values) => updateStringList(key, values)}
    />
  );

  const labColumns: ColumnsType<LabResult> = editable ? [
    {
      title: '指标',
      dataIndex: 'item',
      key: 'item',
      width: 180,
      render: (_, record, index) => (
        <Input value={record.item} onChange={(e) => updateLab(index, 'item', e.target.value)} placeholder="如：血压" allowClear />
      ),
    },
    {
      title: '结果',
      dataIndex: 'value',
      key: 'value',
      render: (_, record, index) => (
        <Input value={record.value} onChange={(e) => updateLab(index, 'value', e.target.value)} placeholder="如：160/95" allowClear />
      ),
    },
    {
      title: '参考范围',
      dataIndex: 'referenceRange',
      key: 'referenceRange',
      render: (_, record, index) => (
        <Input value={record.referenceRange} onChange={(e) => updateLab(index, 'referenceRange', e.target.value)} placeholder="如：90-140" allowClear />
      ),
    },
    {
      title: '单位',
      dataIndex: 'unit',
      key: 'unit',
      width: 150,
      render: (_, record, index) => (
        <Select
          showSearch
          allowClear
          style={{ width: '100%' }}
          value={record.unit || undefined}
          placeholder="单位"
          options={LAB_UNIT_OPTIONS.map((item) => ({ label: item, value: item }))}
          onChange={(value) => updateLab(index, 'unit', value ?? '')}
        />
      ),
    },
    {
      title: '标记',
      dataIndex: 'flag',
      key: 'flag',
      width: 100,
      render: (_, record, index) => (
        <Select
          style={{ width: '100%' }}
          value={record.flag ?? ''}
          options={LAB_FLAG_OPTIONS}
          onChange={(value) => updateLab(index, 'flag', value)}
        />
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      render: (_, __, index) => (
        <Button danger type="text" icon={<DeleteOutlined />} onClick={() => removeLab(index)} />
      ),
    },
  ] : [
    {
      title: '指标',
      dataIndex: 'item',
      key: 'item',
      width: 180,
    },
    {
      title: '结果',
      key: 'value',
      render: (_, record) => (
        <span>
          {record.value || '-'}{record.unit ? ` ${record.unit}` : ''}
          {record.flag ? <Tag color={record.flag === '↑' || record.flag === '+' || record.flag === '异常' ? 'red' : 'blue'} style={{ marginLeft: 8 }}>{record.flag}</Tag> : null}
        </span>
      ),
    },
    {
      title: '参考范围',
      dataIndex: 'referenceRange',
      key: 'referenceRange',
    },
    {
      title: '单位',
      dataIndex: 'unit',
      key: 'unit',
      width: 120,
    },
  ];

  return (
    <>
      {structuredInfo && (
        <Card
          title={
            <span>
              <UserOutlined style={{ marginRight: 8 }} />
              结构化患者信息{editable ? '（请核对后保存）' : ''}
            </span>
          }
          className="patient-summary-card"
          size="small"
        >
          <Descriptions bordered column={2} size="small" className="summary-descriptions">
            <Descriptions.Item label="姓名">{editable ? renderInput('name', '请输入姓名') : renderText(basicInfo.name)}</Descriptions.Item>
            <Descriptions.Item label="年龄">{editable ? renderNumberInput('age', '岁', 0, 130) : renderText(basicInfo.age, '岁')}</Descriptions.Item>
            <Descriptions.Item label="性别">{editable ? renderSelect('gender', GENDER_OPTIONS) : renderText(basicInfo.gender)}</Descriptions.Item>
            <Descriptions.Item label="体重">{editable ? renderNumberInput('weight', 'kg', 0, 300) : renderText(basicInfo.weight, 'kg')}</Descriptions.Item>
            <Descriptions.Item label="医院">{editable ? renderInput('hospital', '请输入医院') : renderText(basicInfo.hospital)}</Descriptions.Item>
            <Descriptions.Item label="科室">{editable ? renderSelect('department', DEPARTMENT_OPTIONS) : renderText(basicInfo.department)}</Descriptions.Item>
            <Descriptions.Item label="主诉" span={2}>{editable ? renderTagEditor('chiefComplaints', '输入后回车添加主诉') : renderTagList(info.chiefComplaints, 'purple')}</Descriptions.Item>
            <Descriptions.Item label="诊断" span={2}>{editable ? renderTagEditor('diagnoses', '输入后回车添加诊断') : renderTagList(info.diagnoses, 'red')}</Descriptions.Item>
            <Descriptions.Item label="当前用药" span={2}>{editable ? renderTagEditor('currentMedications', '输入药名后回车添加') : renderTagList(info.currentMedications, 'green')}</Descriptions.Item>
            <Descriptions.Item label="过敏史" span={2}>{editable ? renderTagEditor('allergies', '输入后回车添加过敏史') : renderTagList(info.allergies, 'orange')}</Descriptions.Item>
            <Descriptions.Item label="既往病史" span={2}>{editable ? renderTagEditor('pastMedicalHistory', '输入后回车添加既往病史') : renderTagList(info.pastMedicalHistory, 'gold')}</Descriptions.Item>
            <Descriptions.Item label="风险因素" span={2}>{editable ? renderTagEditor('riskFactors', '输入后回车添加风险因素') : renderTagList(info.riskFactors, 'volcano')}</Descriptions.Item>
            <Descriptions.Item label="不确定项" span={2}>{editable ? renderTagEditor('uncertainties', '输入后回车添加不确定项') : renderTagList(info.uncertainties, 'default')}</Descriptions.Item>
          </Descriptions>

          <div style={{ marginTop: 16 }}>
            <div className="section-title">检查检验结果</div>
            {editable && (
              <Button icon={<PlusOutlined />} onClick={addLab} style={{ marginBottom: 8 }}>
                添加检验项
              </Button>
            )}
            {labResults.length > 0 ? (
              <Table
                rowKey={(_, index) => `lab-${index}`}
                columns={labColumns}
                dataSource={labResults}
                pagination={{ pageSize: 8, hideOnSinglePage: true }}
                size="small"
                scroll={{ x: editable ? 980 : 720 }}
              />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="未提取到检验指标">
                {editable && <Button type="primary" icon={<PlusOutlined />} onClick={addLab}>添加检验项</Button>}
              </Empty>
            )}
          </div>
        </Card>
      )}

      {patientSummary && !structuredInfo && (
        <Card title="结构化摘要" className="patient-summary-card" size="small">
          <div style={{ whiteSpace: 'pre-wrap', lineHeight: 1.8, fontSize: 13 }}>{patientSummary}</div>
        </Card>
      )}

      {patientSummary && structuredInfo && (
        <Collapse
          ghost
          style={{ marginTop: 8 }}
          items={[
            {
              key: 'raw-json',
              label: '查看原始结构化数据（调试用）',
              children: (
                <Typography.Paragraph
                  copyable={{ text: JSON.stringify(info, null, 2) }}
                  className="raw-json-block"
                >
                  <pre>{JSON.stringify(info, null, 2)}</pre>
                </Typography.Paragraph>
              ),
            },
          ]}
        />
      )}

      {evaluationMetrics && (
        <Card
          title={
            <span>
              <DashboardOutlined style={{ marginRight: 8 }} />
              评估指标
            </span>
          }
          className="patient-summary-card"
          size="small"
        >
          <Descriptions bordered column={3} size="small" className="summary-descriptions">
            <Descriptions.Item label="输入文件数">{evaluationMetrics.inputFileCount}</Descriptions.Item>
            <Descriptions.Item label="解析文本长度">{evaluationMetrics.parsedTextLength}</Descriptions.Item>
            <Descriptions.Item label="检索文档数">{evaluationMetrics.retrievedDocumentCount}</Descriptions.Item>
            <Descriptions.Item label="诊断条数">{evaluationMetrics.extractedDiagnosisCount}</Descriptions.Item>
            <Descriptions.Item label="检验项数">{evaluationMetrics.extractedLabResultCount}</Descriptions.Item>
            <Descriptions.Item label="不确定项数">{evaluationMetrics.uncertaintyCount}</Descriptions.Item>
            <Descriptions.Item label="解析耗时">{evaluationMetrics.parseCostMs} ms</Descriptions.Item>
            <Descriptions.Item label="抽取耗时">{evaluationMetrics.extractCostMs} ms</Descriptions.Item>
            <Descriptions.Item label="检索耗时">{evaluationMetrics.retrievalCostMs} ms</Descriptions.Item>
          </Descriptions>
        </Card>
      )}

      {sources.length > 0 && (
        <Collapse
          style={{ marginTop: 16 }}
          items={[
            {
              key: 'sources',
              label: (
                <span>
                  <BookOutlined style={{ marginRight: 8 }} />
                  参考医学知识
                  <Tag color="blue" style={{ marginLeft: 8 }}>
                    {sources.length} 条
                  </Tag>
                </span>
              ),
              children: (
                <div className="source-list">
                  {sources.map((src, i) => (
                    <div key={i} className="source-item">
                      <div className="source-name-row">
                        <div className="source-name">{src.source}</div>
                        <Space size={8} wrap>
                          {src.sectionTitle ? <Tag color="geekblue">{src.sectionTitle}</Tag> : null}
                          {src.page ? <Tag>第 {src.page} 页</Tag> : null}
                        </Space>
                      </div>
                      <div>{src.content}</div>
                    </div>
                  ))}
                </div>
              ),
            },
          ]}
        />
      )}
    </>
  );
};

export default PatientSummary;
