import { Upload, message } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';

const { Dragger } = Upload;

const ACCEPT_TYPES = [
  'application/pdf',
  'image/jpeg',
  'image/jpg',
  'image/png',
];

interface PdfUploaderProps {
  onFileSelected: (file: File) => void;
  onFilesSelected?: (files: File[]) => void;
  multiple?: boolean;
  disabled?: boolean;
  hint?: string;
  acceptImages?: boolean;
}

const PdfUploader: React.FC<PdfUploaderProps> = ({
  onFileSelected,
  onFilesSelected,
  multiple = false,
  disabled,
  hint,
  acceptImages = false,
}) => {
  const acceptStr = acceptImages ? '.pdf,.jpg,.jpeg,.png' : '.pdf';

  const beforeUpload = (file: UploadFile) => {
    const type = (file as unknown as File).type;
    const isAccepted = acceptImages
      ? ACCEPT_TYPES.includes(type)
      : type === 'application/pdf';

    if (!isAccepted) {
      message.error(acceptImages ? '仅支持 PDF 和图片格式文件' : '仅支持 PDF 格式文件');
      return Upload.LIST_IGNORE;
    }

    if (!multiple) {
      onFileSelected(file as unknown as File);
    }
    return false;
  };

  const handleChange = (info: { fileList: UploadFile[] }) => {
    if (multiple && onFilesSelected && info.fileList.length > 0) {
      const rawFiles = info.fileList
        .map((f) => f.originFileObj)
        .filter((f): f is File => !!f);
      onFilesSelected(rawFiles);
    }
  };

  return (
    <Dragger
      accept={acceptStr}
      multiple={multiple}
      showUploadList={multiple}
      maxCount={multiple ? 10 : 1}
      beforeUpload={beforeUpload}
      onChange={multiple ? handleChange : undefined}
      disabled={disabled}
      style={{ padding: '20px 0' }}
    >
      <p className="ant-upload-drag-icon">
        <InboxOutlined />
      </p>
      <p className="ant-upload-text">
        点击或拖拽文件到此区域{acceptImages ? '（支持 PDF 和图片）' : ''}
      </p>
      <p className="ant-upload-hint">
        {hint || (acceptImages
          ? '支持 PDF 文档和 JPG/PNG 图片格式'
          : '支持医学指南、药品说明书、诊断书、病历等 PDF 文档')}
      </p>
    </Dragger>
  );
};

export default PdfUploader;
