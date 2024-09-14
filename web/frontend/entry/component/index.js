import React from 'react';
import cx from 'classnames';
import { Upload, Button, Layout, Menu, Radio } from 'antd';
import ContentDiff from '../contentDiff';
import DiffPanel from '../diffPanel/index1';
var jsDiff = require('diff');

const TAB = {
    LINES: '0',
}

//  传统写法
class ShowComponent extends React.Component {
    state = {
        currentTab: TAB.LINES
    }

    getContent = () => {
        const contentMap = {
            [TAB.LINES]: () => <div><DiffPanel type='lines'/></div>,
        };
        return contentMap[this.state.currentTab]();
    }

    navChange = (e) => {
        this.setState({
            currentTab: e.key
        })
    }

    render() {
        return <Layout>
            <Menu onClick={this.navChange} mode='horizontal' selectedKeys={[this.state.currentTab]}>
                <Menu.Item key={TAB.LINES}>Refactoring Discovery</Menu.Item>
            </Menu>
            {this.getContent()}
        </Layout>
    }
}

export default ShowComponent;