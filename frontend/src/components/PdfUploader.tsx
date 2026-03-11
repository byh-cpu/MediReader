import { Upload, message } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';

const { Dragger } = Upload;

interface PdfUploaderProps {
  onFileSelected: (file: File) => void;
  disabled?: boolean;
  hint?: string;
}

const PdfUploader: React.FC<PdfUploaderProps> = ({ onFileSelected, disabled, hint }) => {
  const beforeUpload = (file: UploadFile) => {
    const isPdf = file.type === 'application/pdf';
    if (!isPdf) {
      message.error('仅支持 PDF 格式文件');
      return Upload.LIST_IGNORE;
    }
    const rawFile = file as unknown as File;
    onFileSelected(rawFile);
    return false;
  };

  return (
    <Dragger
      accept=".pdf"
      showUploadList={false}
      beforeUpload={beforeUpload}
      disabled={disabled}
      style={{ padding: '20px 0' }}
    >
      <p className="ant-upload-drag-icon">
        <InboxOutlined />
      </p>
      <p className="ant-upload-text">点击或拖拽 PDF 文件到此区域</p>
      <p className="ant-upload-hint">
        {hint || '支持医学指南、药品说明书、诊断书、病历等 PDF 文档'}
      </p>
    </Dragger>
  );
};

export default PdfUploader;
