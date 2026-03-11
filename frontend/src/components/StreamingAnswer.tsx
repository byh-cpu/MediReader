import { Card } from 'antd';
import ReactMarkdown from 'react-markdown';

interface StreamingAnswerProps {
  content: string;
  isStreaming: boolean;
  title?: string;
}

const StreamingAnswer: React.FC<StreamingAnswerProps> = ({
  content,
  isStreaming,
  title = '用药建议',
}) => {
  if (!content) return null;

  return (
    <Card
      title={title}
      style={{ marginTop: 16 }}
      styles={{ body: { maxHeight: 600, overflowY: 'auto' } }}
    >
      <div className="streaming-content">
        <ReactMarkdown>{content}</ReactMarkdown>
        {isStreaming && <span className="cursor-blink" />}
      </div>
    </Card>
  );
};

export default StreamingAnswer;
