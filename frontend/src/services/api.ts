import { fetchEventSource } from '@microsoft/fetch-event-source';

const BASE_URL = '/api';

export interface WorkflowEvent {
  step: string;
  status: string;
  message?: string;
  data?: Record<string, unknown>;
  token?: string;
}

export interface KnowledgeDocument {
  id: string;
  fileName: string;
  fileSize: number;
  chunkCount: number;
  uploadTime: string;
}

export interface UploadResponse {
  id: string;
  fileName: string;
  chunkCount: number;
  message: string;
}

export interface SourceItem {
  content: string;
  source: string;
  sectionTitle?: string;
  page?: string;
}

export interface BasicInfo {
  name?: string;
  age?: string;
  gender?: string;
  weight?: string;
  hospital?: string;
  department?: string;
}

export interface LabResult {
  item?: string;
  value?: string;
  referenceRange?: string;
  unit?: string;
  flag?: string;
}

export interface StructuredInfo {
  basicInfo?: BasicInfo;
  chiefComplaints?: string[];
  diagnoses?: string[];
  currentMedications?: string[];
  allergies?: string[];
  pastMedicalHistory?: string[];
  labResults?: LabResult[];
  riskFactors?: string[];
  uncertainties?: string[];
  evidence?: string[];
}

export interface EvaluationMetrics {
  inputFileCount: number;
  parsedTextLength: number;
  retrievedDocumentCount: number;
  extractedDiagnosisCount: number;
  extractedLabResultCount: number;
  uncertaintyCount: number;
  parseCostMs: number;
  extractCostMs: number;
  retrievalCostMs: number;
}

export const uploadKnowledge = async (file: File): Promise<UploadResponse> => {
  const formData = new FormData();
  formData.append('file', file);
  const response = await fetch(`${BASE_URL}/knowledge/upload`, {
    method: 'POST',
    body: formData,
  });
  if (!response.ok) {
    const err = await response.json();
    throw new Error(err.message || '上传失败');
  }
  return response.json();
};

export const listKnowledge = async (): Promise<KnowledgeDocument[]> => {
  const response = await fetch(`${BASE_URL}/knowledge/list`);
  if (!response.ok) throw new Error('获取列表失败');
  return response.json();
};

export const deleteKnowledge = async (id: string): Promise<void> => {
  const response = await fetch(`${BASE_URL}/knowledge/${id}`, {
    method: 'DELETE',
  });
  if (!response.ok) {
    const err = await response.json();
    throw new Error(err.message || '删除失败');
  }
};

export const analyzeConsultation = (
  files: File | File[],
  onEvent: (event: WorkflowEvent) => void,
  onError?: (error: unknown) => void,
  onComplete?: () => void,
): AbortController => {
  const formData = new FormData();
  const fileList = Array.isArray(files) ? files : [files];
  for (const f of fileList) {
    formData.append('files', f);
  }

  const ctrl = new AbortController();

  fetchEventSource(`${BASE_URL}/consultation/analyze`, {
    method: 'POST',
    body: formData,
    signal: ctrl.signal,
    openWhenHidden: true,
    onmessage(ev) {
      if (ev.data) {
        try {
          const event: WorkflowEvent = JSON.parse(ev.data);
          onEvent(event);
        } catch {
          console.warn('Failed to parse SSE event:', ev.data);
        }
      }
    },
    onerror(err) {
      onError?.(err);
      throw err;
    },
    onclose() {
      onComplete?.();
    },
  });

  return ctrl;
};
