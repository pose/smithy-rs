/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use aws_sdk_s3::operation::get_object::{GetObjectError, GetObjectOutput};
use aws_smithy_runtime::{BoxError, BoxFallibleFut, RetryStrategy};
use aws_smithy_runtime_api::config_bag::ConfigBag;
use aws_smithy_runtime_api::runtime_plugin::RuntimePlugin;

#[derive(Debug)]
pub struct GetObjectRetryStrategy {}

impl GetObjectRetryStrategy {
    pub fn new() -> Self {
        Self {}
    }
}

impl RuntimePlugin for GetObjectRetryStrategy {
    fn configure(&self, _cfg: &mut ConfigBag) -> Result<(), BoxError> {
        // TODO put a retry strategy in the bag
        Ok(())
    }
}

impl RetryStrategy<Result<GetObjectOutput, GetObjectError>> for GetObjectRetryStrategy {
    fn should_attempt_initial_request(&self, _cfg: &ConfigBag) -> BoxFallibleFut<()> {
        todo!()
    }

    fn should_attempt_retry(
        &self,
        _res: &Result<GetObjectOutput, GetObjectError>,
        _cfg: &ConfigBag,
    ) -> BoxFallibleFut<bool> {
        todo!()
    }
}

//     retry_classifier: Arc::new(
//         |res: Result<&SdkSuccess<GetObjectOutput>, &SdkError<GetObjectError>>| -> RetryKind {
//             let classifier = AwsResponseRetryClassifier::new();
//             classifier.classify_retry(res)
//         },
//     ),
