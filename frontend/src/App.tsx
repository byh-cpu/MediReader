import { useState } from 'react';
import { Layout, Menu } from 'antd';
import { BookOutlined, MedicineBoxOutlined } from '@ant-design/icons';
import KnowledgePage from './pages/KnowledgePage';
import ConsultationPage from './pages/ConsultationPage';

const { Header, Sider, Content } = Layout;

function App() {
  const [currentPage, setCurrentPage] = useState('consultation');

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider width={220} theme="dark">
        <div className="logo">MediReader</div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[currentPage]}
          onClick={(e) => setCurrentPage(e.key)}
          style={{ marginTop: 8 }}
          items={[
            {
              key: 'consultation',
              icon: <MedicineBoxOutlined />,
              label: '智能用药咨询',
            },
            {
              key: 'knowledge',
              icon: <BookOutlined />,
              label: '知识库管理',
            },
          ]}
        />
      </Sider>
      <Layout>
        <Header style={{ background: '#fff', padding: '0 24px', borderBottom: '1px solid #f0f0f0' }}>
          <div className="page-header">
            {currentPage === 'knowledge' ? '医学知识库管理' : '智能用药咨询'}
          </div>
        </Header>
        <Content style={{ margin: 24, padding: 24, background: '#fff', borderRadius: 8, minHeight: 360 }}>
          {currentPage === 'knowledge' ? <KnowledgePage /> : <ConsultationPage />}
        </Content>
      </Layout>
    </Layout>
  );
}

export default App;
