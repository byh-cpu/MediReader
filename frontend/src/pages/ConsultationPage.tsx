import { useState, useRef, useCallback } from 'react';
import { Card, Button, message, Alert, Divider } from 'antd';
import { ClearOutlined, FileTextOutlined } from '@ant-design/icons';
import PdfUploader from '../components/PdfUploader';
import WorkflowSteps from '../components/WorkflowSteps';
import StreamingAnswer from '../components/StreamingAnswer';
import PatientSummary from '../components/PatientSummary';
import { analyzeConsultation, WorkflowEvent } from '../services/api';

const STEP_ORDER = ['PDF_PARSING', 'INFO_EXTRACTION', 'RAG_RETRIEVAL', 'LLM_ANALYSIS'];

interface Source {
  content: string;
  source: string;
}

const ConsultationPage: React.FC = () => {
  const [analyzing, setAnalyzing] = useState(false);
  const [currentStep, setCurrentStep] = useState(-1);
  const [stepStatuses, setStepStatuses] = useState<Record<string, string>>({});
  const [patientSummary, setPatientSummary] = useState('');
  const [sources, setSources] = useState<Source[]>([]);
  const [streamingText, setStreamingText] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  const [completed, setCompleted] = useState(false);
  const [fileName, setFileName] = useState('');
  const ctrlRef = useRef<AbortController | null>(null);

  const reset = useCallback(() => {
    if (ctrlRef.current) {
      ctrlRef.current.abort();
      ctrlRef.current = null;
    }
    setAnalyzing(false);
    setCurrentStep(-1);
    setStepStatuses({});
    setPatientSummary('');
    setSources([]);
    setStreamingText('');
    setIsStreaming(false);
    setCompleted(false);
    setFileName('');
  }, []);

  const handleEvent = useCallback((event: WorkflowEvent) => {
    const stepIndex = STEP_ORDER.indexOf(event.step);

    switch (event.status) {
      case 'running':
        setCurrentStep(stepIndex);
        setStepStatuses((prev) => ({ ...prev, [event.step]: 'running' }));
        if (event.step === 'LLM_ANALYSIS') {
          setIsStreaming(true);
        }
        break;

      case 'done':
        setStepStatuses((prev) => ({ ...prev, [event.step]: 'done' }));
        if (event.step === 'INFO_EXTRACTION' && event.data?.patientSummary) {
          setPatientSummary(event.data.patientSummary as string);
        }
        if (event.step === 'RAG_RETRIEVAL' && event.data?.sources) {
          setSources(event.data.sources as Source[]);
        }
        if (event.step === 'LLM_ANALYSIS') {
          setIsStreaming(false);
          setCompleted(true);
          setAnalyzing(false);
        }
        break;

      case 'streaming':
        if (event.token) {
          setStreamingText((prev) => prev + event.token);
        }
        break;

      case 'error':
        setStepStatuses((prev) => ({ ...prev, [event.step]: 'error' }));
        message.error(event.message || '分析出错');
        setIsStreaming(false);
        setAnalyzing(false);
        break;
    }
  }, []);

  const handleFileSelected = (file: File) => {
    reset();
    setFileName(file.name);
    setAnalyzing(true);
    setCurrentStep(0);

    ctrlRef.current = analyzeConsultation(
      file,
      handleEvent,
      () => {
        message.error('连接异常，请检查后端服务是否正常运行');
        setAnalyzing(false);
        setIsStreaming(false);
      },
      () => {
        setAnalyzing(false);
        setIsStreaming(false);
      },
    );
  };

  const hasStarted = currentStep >= 0;

  return (
    <div>
      <Alert
        message="使用说明"
        description="请先在「知识库管理」页面上传医学指南或药品说明书，然后在此页面上传患者诊断书或病历 PDF，系统将自动分析并给出用药建议。"
        type="info"
        showIcon
        closable
        style={{ marginBottom: 24 }}
      />

      <div className="upload-section">
        <PdfUploader
          onFileSelected={handleFileSelected}
          disabled={analyzing}
          hint="上传患者诊断书或病历 PDF，系统将自动提取信息并生成用药建议"
        />
      </div>

      {hasStarted && (
        <>
          {fileName && (
            <div style={{ marginBottom: 16, color: '#666' }}>
              <FileTextOutlined style={{ marginRight: 8 }} />
              正在分析: <strong>{fileName}</strong>
            </div>
          )}

          <WorkflowSteps currentStep={currentStep} stepStatuses={stepStatuses} />

          <Divider />

          <PatientSummary patientSummary={patientSummary} sources={sources} />

          <StreamingAnswer content={streamingText} isStreaming={isStreaming} />

          {completed && (
            <div style={{ marginTop: 24, textAlign: 'center' }}>
              <Button icon={<ClearOutlined />} onClick={reset} size="large">
                开始新的咨询
              </Button>
            </div>
          )}
        </>
      )}

      {!hasStarted && (
        <div className="empty-state">
          <FileTextOutlined />
          <p>上传诊断书或病历开始智能用药咨询</p>
        </div>
      )}
    </div>
  );
};

export default ConsultationPage;
