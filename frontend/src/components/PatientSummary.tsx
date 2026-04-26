import { Card, Collapse, Descriptions, Empty, Space, Table, Tag, Typography } from 'antd';
import { UserOutlined, BookOutlined, DashboardOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { EvaluationMetrics, LabResult, SourceItem, StructuredInfo } from '../services/api';

interface PatientSummaryProps {
  patientSummary: string;
  structuredInfo?: StructuredInfo | null;
  sources: SourceItem[];
  evaluationMetrics?: EvaluationMetrics | null;
}

const renderTagList = (values?: string[], color = 'blue') => {
  if (!values || values.length === 0) return <span className="muted-text">未提供</span>;
  return (
    <Space wrap>
      {values.map((item) => (
        <Tag key={item} color={color}>
          {item}
        </Tag>
      ))}
    </Space>
  );
};

const labColumns: ColumnsType<LabResult> = [
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
        {record.value || '-'}
        {record.flag ? <Tag color={record.flag === '↑' ? 'red' : 'blue'} style={{ marginLeft: 8 }}>{record.flag}</Tag> : null}
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

const PatientSummary: React.FC<PatientSummaryProps> = ({
  patientSummary,
  structuredInfo,
  sources,
  evaluationMetrics,
}) => {
  const basicInfo = structuredInfo?.basicInfo;
  const labResults = structuredInfo?.labResults ?? [];

  return (
    <>
      {structuredInfo && (
        <Card
          title={
            <span>
              <UserOutlined style={{ marginRight: 8 }} />
              结构化患者信息
            </span>
          }
          className="patient-summary-card"
          size="small"
        >
          <Descriptions bordered column={2} size="small" className="summary-descriptions">
            <Descriptions.Item label="姓名">{basicInfo?.name || '未提供'}</Descriptions.Item>
            <Descriptions.Item label="年龄">{basicInfo?.age || '未提供'}</Descriptions.Item>
            <Descriptions.Item label="性别">{basicInfo?.gender || '未提供'}</Descriptions.Item>
            <Descriptions.Item label="体重">{basicInfo?.weight || '未提供'}</Descriptions.Item>
            <Descriptions.Item label="医院">{basicInfo?.hospital || '未提供'}</Descriptions.Item>
            <Descriptions.Item label="科室">{basicInfo?.department || '未提供'}</Descriptions.Item>
            <Descriptions.Item label="主诉" span={2}>{renderTagList(structuredInfo.chiefComplaints, 'purple')}</Descriptions.Item>
            <Descriptions.Item label="诊断" span={2}>{renderTagList(structuredInfo.diagnoses, 'red')}</Descriptions.Item>
            <Descriptions.Item label="当前用药" span={2}>{renderTagList(structuredInfo.currentMedications, 'green')}</Descriptions.Item>
            <Descriptions.Item label="过敏史" span={2}>{renderTagList(structuredInfo.allergies, 'orange')}</Descriptions.Item>
            <Descriptions.Item label="既往病史" span={2}>{renderTagList(structuredInfo.pastMedicalHistory, 'gold')}</Descriptions.Item>
            <Descriptions.Item label="风险因素" span={2}>{renderTagList(structuredInfo.riskFactors, 'volcano')}</Descriptions.Item>
            <Descriptions.Item label="不确定项" span={2}>{renderTagList(structuredInfo.uncertainties, 'default')}</Descriptions.Item>
          </Descriptions>

          <div style={{ marginTop: 16 }}>
            <div className="section-title">检查检验结果</div>
            {labResults.length > 0 ? (
              <Table
                rowKey={(record, index) => `${record.item ?? 'lab'}-${index}`}
                columns={labColumns}
                dataSource={labResults}
                pagination={{ pageSize: 8, hideOnSinglePage: true }}
                size="small"
                scroll={{ x: 720 }}
              />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="未提取到检验指标" />
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
                  copyable={{ text: patientSummary }}
                  className="raw-json-block"
                >
                  <pre>{patientSummary}</pre>
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
