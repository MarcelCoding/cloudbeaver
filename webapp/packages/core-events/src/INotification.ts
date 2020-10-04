/*
 * cloudbeaver - Cloud Database Manager
 * Copyright (C) 2020 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */

export enum ENotificationType {
  Info = 'Info',
  Error = 'Error',
  Success = 'Success',
  Custom =' Custom'
}

export type INotificationExtraProps<T> = Record<string, any> & {
  source?: T;
}

export type NotificationComponentProps<TSource = undefined, TProps extends INotificationExtraProps<
TSource> = Record<string, any>> = {
  notification: INotification<TSource, TProps>;
  onClose: () => void;
}

export type NotificationComponent<
  TSource = undefined,
  TProps = Record<string, any>,
> = React.FunctionComponent<NotificationComponentProps<TSource> & TProps>

export interface INotification<TSource = undefined, TProps extends INotificationExtraProps<
TSource> = Record<string, any>> {
  readonly id: number;
  type: ENotificationType;
  title: string;
  message?: string;
  details?: string | Error;
  persistent?: boolean;
  isSilent: boolean;
  customComponent?: () => NotificationComponent<TSource, TProps>;
  close: () => void;
  showDetails: () => void;
  extraProps?: TProps;
}

export interface INotificationOptions<TSource = undefined, TProps extends INotificationExtraProps<
TSource> = Record<string, any>> {
  title: string;
  message?: string;
  details?: string | Error;
  isSilent?: boolean;
  persistent?: boolean;
  customComponent?: () => NotificationComponent<TSource, TProps>;
  extraProps?: TProps;
}
