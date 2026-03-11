import { Steps } from 'antd';
import {
  FileSearchOutlined,
  UserOutlined,
  SearchOutlined,
  MedicineBoxOutlined,
} from '@ant-design/icons';

interface WorkflowStepsProps {
  currentStep: number;
  stepStatuses: Record<string, string>;
  stepMessages?: Record<string, string>;
}

const STEPS = [
  { key: 'PDF_PARSING', title: '文档解析', icon: <FileSearchOutlined /> },
  { key: 'INFO_EXTRACTION', title: '信息提取', icon: <UserOutlined /> },
  { key: 'RAG_RETRIEVAL', title: '知识检索', icon: <SearchOutlined /> },
  { key: 'LLM_ANALYSIS', title: '用药分析', icon: <MedicineBoxOutlined /> },
];

const WorkflowSteps: React.FC<WorkflowStepsProps> = ({ currentStep, stepStatuses, stepMessages }) => {
  const mapStatus = (key: string, index: number): 'wait' | 'process' | 'finish' | 'error' => {
    const s = stepStatuses[key];
    if (s === 'finish' || s === 'done') return 'finish';
    if (s === 'error') return 'error';
    if (s === 'process' || s === 'running' || s === 'streaming') return 'process';
    if (index < currentStep) return 'finish';
    return 'wait';
  };

  return (
    <div className="workflow-container">
      <Steps
        current={currentStep}
        items={STEPS.map((step, index) => ({
          title: step.title,
          description: stepStatuses[step.key] === 'running' ? stepMessages?.[step.key] : undefined,
          icon: step.icon,
          status: mapStatus(step.key, index),
        }))}
      />
    </div>
  );
};

export default WorkflowSteps;
