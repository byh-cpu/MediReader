import { Card, Collapse, Tag } from 'antd';
import { UserOutlined, BookOutlined } from '@ant-design/icons';

interface Source {
  content: string;
  source: string;
}

interface PatientSummaryProps {
  patientSummary: string;
  sources: Source[];
}

const PatientSummary: React.FC<PatientSummaryProps> = ({ patientSummary, sources }) => {
  return (
    <>
      {patientSummary && (
        <Card
          title={
            <span>
              <UserOutlined style={{ marginRight: 8 }} />
              患者信息摘要
            </span>
          }
          className="patient-summary-card"
          size="small"
        >
          <div style={{ whiteSpace: 'pre-wrap', lineHeight: 1.8, fontSize: 13 }}>
            {patientSummary}
          </div>
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
                      <div className="source-name">{src.source}</div>
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
