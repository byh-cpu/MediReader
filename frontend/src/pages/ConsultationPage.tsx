import { useState, useRef, useCallback } from 'react';
import { Button, message, Alert, Divider, Image, Space, Tag } from 'antd';
import { CheckCircleOutlined, ClearOutlined, FileTextOutlined, PictureOutlined, FilePdfOutlined } from '@ant-design/icons';
import PdfUploader from '../components/PdfUploader';
import WorkflowSteps from '../components/WorkflowSteps';
import StreamingAnswer from '../components/StreamingAnswer';
import PatientSummary from '../components/PatientSummary';
import {
  analyzeConsultation,
  continueConsultation,
  type EvaluationMetrics,
  type SourceItem,
  type StructuredInfo,
  type WorkflowEvent,
} from '../services/api';

const STEP_ORDER = ['PDF_PARSING', 'INFO_EXTRACTION', 'RAG_RETRIEVAL', 'LLM_ANALYSIS'];

const isImageFile = (file: File) => file.type.startsWith('image/');

const ConsultationPage: React.FC = () => {
  const [analyzing, setAnalyzing] = useState(false);
  const [currentStep, setCurrentStep] = useState(-1);
  const [stepStatuses, setStepStatuses] = useState<Record<string, string>>({});
  const [patientSummary, setPatientSummary] = useState('');
  const [structuredInfo, setStructuredInfo] = useState<StructuredInfo | null>(null);
  const [sources, setSources] = useState<SourceItem[]>([]);
  const [evaluationMetrics, setEvaluationMetrics] = useState<EvaluationMetrics | null>(null);
  const [streamingText, setStreamingText] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  const [completed, setCompleted] = useState(false);
  const [awaitingReview, setAwaitingReview] = useState(false);
  const [continuing, setContinuing] = useState(false);
  const [stepMessages, setStepMessages] = useState<Record<string, string>>({});
  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);
  const [previewUrls, setPreviewUrls] = useState<string[]>([]);
  const ctrlRef = useRef<AbortController | null>(null);

  const reset = useCallback(() => {
    if (ctrlRef.current) {
      ctrlRef.current.abort();
      ctrlRef.current = null;
    }
    setAnalyzing(false);
    setCurrentStep(-1);
    setStepStatuses({});
    setStepMessages({});
    setPatientSummary('');
    setStructuredInfo(null);
    setSources([]);
    setEvaluationMetrics(null);
    setStreamingText('');
    setIsStreaming(false);
    setCompleted(false);
    setAwaitingReview(false);
    setContinuing(false);
    setSelectedFiles([]);
    previewUrls.forEach((url) => URL.revokeObjectURL(url));
    setPreviewUrls([]);
  }, [previewUrls]);

  const handleEvent = useCallback((event: WorkflowEvent) => {
    const stepIndex = STEP_ORDER.indexOf(event.step);

    switch (event.status) {
      case 'running':
        setCurrentStep(stepIndex);
        setStepStatuses((prev) => ({ ...prev, [event.step]: 'running' }));
        if (event.message) {
          setStepMessages((prev) => ({ ...prev, [event.step]: event.message! }));
        }
        if (event.step === 'LLM_ANALYSIS') {
          setIsStreaming(true);
          if (event.data?.evaluationMetrics) {
            setEvaluationMetrics(event.data.evaluationMetrics as EvaluationMetrics);
          }
        }
        break;

      case 'done':
        setStepStatuses((prev) => ({ ...prev, [event.step]: 'done' }));
        if (event.message) {
          setStepMessages((prev) => ({ ...prev, [event.step]: event.message! }));
        }
        if (event.step === 'INFO_EXTRACTION') {
          if (event.data?.patientSummary) {
            setPatientSummary(event.data.patientSummary as string);
          }
          if (event.data?.structuredInfo) {
            setStructuredInfo(event.data.structuredInfo as StructuredInfo);
          }
          if (event.data?.awaitingReview) {
            setAwaitingReview(true);
            setAnalyzing(false);
            message.success('患者信息已识别，请核对修改后继续分析');
          }
        }
        if (event.step === 'RAG_RETRIEVAL' && event.data?.sources) {
          setSources(event.data.sources as SourceItem[]);
        }
        if (event.step === 'LLM_ANALYSIS') {
          setIsStreaming(false);
          setCompleted(true);
          setAnalyzing(false);
          setContinuing(false);
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
        setContinuing(false);
        break;
    }
  }, []);

  const startAnalysis = (files: File[]) => {
    if (files.length === 0) return;
    reset();

    const urls = files.filter(isImageFile).map((f) => URL.createObjectURL(f));
    setPreviewUrls(urls);
    setSelectedFiles(files);
    setAnalyzing(true);
    setAwaitingReview(false);
    setCurrentStep(0);

    ctrlRef.current = analyzeConsultation(
      files,
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

  const handleFileSelected = (file: File) => {
    startAnalysis([file]);
  };

  const continueWithReviewedInfo = () => {
    if (!structuredInfo) {
      message.warning('请先完成患者信息识别');
      return;
    }
    setAwaitingReview(false);
    setContinuing(true);
    setCurrentStep(2);
    setStepStatuses((prev) => ({ ...prev, RAG_RETRIEVAL: 'running' }));
    setSources([]);
    setEvaluationMetrics(null);
    setStreamingText('');
    setIsStreaming(false);
    setCompleted(false);

    ctrlRef.current = continueConsultation(
      structuredInfo,
      handleEvent,
      () => {
        message.error('连接异常，请检查后端服务是否正常运行');
        setContinuing(false);
        setIsStreaming(false);
      },
      () => {
        setContinuing(false);
        setIsStreaming(false);
      },
    );
  };

  const hasStarted = currentStep >= 0;

  return (
    <div>
      <Alert
        message="使用说明"
        description="请先在「知识库管理」页面上传医学指南或药品说明书，然后在此页面上传患者诊断书或病历（支持 PDF 和图片），系统将自动分析并给出用药建议。"
        type="info"
        showIcon
        closable
        style={{ marginBottom: 24 }}
      />

      <div className="upload-section">
        <PdfUploader
          onFileSelected={handleFileSelected}
          disabled={analyzing || continuing}
          acceptImages
          hint="上传患者诊断书、病历 PDF 或检查报告照片，系统将自动识别并生成用药建议"
        />
      </div>

      {hasStarted && (
        <>
          {selectedFiles.length > 0 && (
            <div style={{ marginBottom: 16 }}>
              <Space wrap>
                {selectedFiles.map((f, i) => (
                  <Tag
                    key={i}
                    icon={isImageFile(f) ? <PictureOutlined /> : <FilePdfOutlined />}
                    color={isImageFile(f) ? 'orange' : 'blue'}
                  >
                    {f.name}
                  </Tag>
                ))}
              </Space>
            </div>
          )}

          {previewUrls.length > 0 && (
            <div style={{ marginBottom: 16 }}>
              <Image.PreviewGroup>
                <Space wrap>
                  {previewUrls.map((url, i) => (
                    <Image key={i} src={url} height={120} style={{ borderRadius: 8 }} />
                  ))}
                </Space>
              </Image.PreviewGroup>
            </div>
          )}

          <WorkflowSteps currentStep={currentStep} stepStatuses={stepStatuses} stepMessages={stepMessages} />

          <Divider />

          <PatientSummary
            patientSummary={patientSummary}
            structuredInfo={structuredInfo}
            sources={sources}
            evaluationMetrics={evaluationMetrics}
            editable={awaitingReview}
            onStructuredInfoChange={setStructuredInfo}
          />

          {awaitingReview && structuredInfo && (
            <div style={{ marginTop: 16, marginBottom: 16, textAlign: 'center' }}>
              <Space>
                <Button icon={<ClearOutlined />} onClick={reset}>
                  重新上传
                </Button>
                <Button
                  type="primary"
                  size="large"
                  icon={<CheckCircleOutlined />}
                  onClick={continueWithReviewedInfo}
                  loading={continuing}
                >
                  保存并继续用药分析
                </Button>
              </Space>
            </div>
          )}

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
          <p>上传诊断书、病历或检查报告照片开始智能用药咨询</p>
        </div>
      )}
    </div>
  );
};

export default ConsultationPage;
