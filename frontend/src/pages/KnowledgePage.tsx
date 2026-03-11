import { useEffect, useState } from 'react';
import { Table, Button, message, Popconfirm, Space, Tag, Spin } from 'antd';
import { DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import PdfUploader from '../components/PdfUploader';
import { uploadKnowledge, listKnowledge, deleteKnowledge, KnowledgeDocument } from '../services/api';

const KnowledgePage: React.FC = () => {
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);

  const fetchDocuments = async () => {
    setLoading(true);
    try {
      const docs = await listKnowledge();
      setDocuments(docs);
    } catch {
      message.error('获取文档列表失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchDocuments();
  }, []);

  const handleUpload = async (file: File) => {
    setUploading(true);
    try {
      const res = await uploadKnowledge(file);
      message.success(res.message);
      fetchDocuments();
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : '上传失败';
      message.error(errorMessage);
    } finally {
      setUploading(false);
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await deleteKnowledge(id);
      message.success('删除成功');
      fetchDocuments();
    } catch {
      message.error('删除失败');
    }
  };

  const formatFileSize = (bytes: number) => {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  };

  const columns = [
    {
      title: '文件名',
      dataIndex: 'fileName',
      key: 'fileName',
      ellipsis: true,
    },
    {
      title: '文件大小',
      dataIndex: 'fileSize',
      key: 'fileSize',
      width: 120,
      render: (size: number) => formatFileSize(size),
    },
    {
      title: '文本块数',
      dataIndex: 'chunkCount',
      key: 'chunkCount',
      width: 100,
      render: (count: number) => <Tag color="blue">{count} 块</Tag>,
    },
    {
      title: '上传时间',
      dataIndex: 'uploadTime',
      key: 'uploadTime',
      width: 180,
      render: (time: string) => time?.replace('T', ' ').substring(0, 19),
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      render: (_: unknown, record: KnowledgeDocument) => (
        <Popconfirm
          title="确定删除此文档？"
          description="删除后相关知识将从向量库中移除"
          onConfirm={() => handleDelete(record.id)}
          okText="确定"
          cancelText="取消"
        >
          <Button type="link" danger icon={<DeleteOutlined />} size="small">
            删除
          </Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <div>
      <div className="upload-section">
        <Spin spinning={uploading} tip="正在解析并构建索引...">
          <PdfUploader
            onFileSelected={handleUpload}
            disabled={uploading}
            hint="上传医学指南、药品说明书等 PDF 文档到知识库"
          />
        </Spin>
      </div>

      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ReloadOutlined />} onClick={fetchDocuments} loading={loading}>
          刷新列表
        </Button>
        <span style={{ color: '#999' }}>共 {documents.length} 篇文档</span>
      </Space>

      <Table
        columns={columns}
        dataSource={documents}
        rowKey="id"
        loading={loading}
        pagination={{ pageSize: 10 }}
        locale={{ emptyText: '暂无文档，请上传医学指南或药品说明书' }}
      />
    </div>
  );
};

export default KnowledgePage;
